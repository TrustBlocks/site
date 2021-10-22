;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.graphql
  (:require
   [juxt.grab.alpha.schema :as schema]
   [juxt.grab.alpha.document :as document]
   [juxt.grab.alpha.execution :refer [execute-request]]
   [juxt.grab.alpha.parser :as parser]
   [jsonista.core :as json]
   [clojure.string :as str]
   [crux.api :as xt]
   [clojure.tools.logging :as log]
   [clojure.walk :refer [postwalk]]
   [crux.api :as x]))

(alias 'site (create-ns 'juxt.site.alpha))
(alias 'http (create-ns 'juxt.http.alpha))
(alias 'grab (create-ns 'juxt.grab.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))
(alias 'g (create-ns 'juxt.grab.alpha.graphql))

(defn to-xt-query [query]
  (postwalk
   (fn [x]
     (cond-> x
       (and (map? x) (:keyword x))
       (-> (get :keyword) keyword)

       (and (map? x) (::g/name x))
       (-> (get ::g/name) symbol))
     )
   query))

(defn query [schema document operation-name db subject]
  (execute-request
   {:schema schema
    :document document
    :operation-name operation-name
    :field-resolver
    (fn [{:keys [object-value field-name] :as args}]
      (let [lookup-type (::schema/provided-types schema)
            field (get-in args [:object-type ::schema/fields-by-name (get-in args [:field-name])])
            site-args (get-in field [::schema/directives-by-name "site" ::g/arguments])
            field-kind (-> field ::g/type-ref ::g/name lookup-type ::g/kind)
            lookup-entity (fn [id] (xt/entity db id))]

        (cond
          (get site-args "q")
          (let [object-id (:crux.db/id object-value)
                q (assoc
                   (to-xt-query (get site-args "q"))
                   :in (vec (cond->> (map symbol (keys (:argument-values args)))
                              object-id (concat ['object]))))
                results (for [[e] (apply
                                   xt/q db q (cond->> (vals (:argument-values args))
                                               object-id (concat [object-id])))]
                          (xt/entity db e))]
            ;; If this isn't a list type, take the first
            (cond-> results
              (not (-> field ::g/type-ref ::g/list-type)) first))

          (get site-args "a")
          (let [att (get site-args "a")
                val (get object-value (keyword att))]
            (if (= field-kind :object)
              (lookup-entity val)
              val))

          (get site-args "resolver")
          (let [resolver (requiring-resolve (symbol (get site-args "resolver")))]
            (resolver (assoc args ::pass/subject subject :db db)))

          ;; Another strategy is to see if the field indexes the
          ;; object-value. This strategy allows for delays to be used to prevent
          ;; computing field values that aren't resolved.
          (contains? object-value field-name)
          (let [f (force (get object-value field-name))]
            (if (fn? f) (f (:argument-values args)) f))

          ;; Or simply try to extract the keyword
          (contains? object-value (keyword field-name))
          (get object-value (keyword field-name))

          :else
          (throw
           (ex-info
            (format "TODO: resolve field: %s" (:field-name args)) args)))))}))


(defn post-handler [{::site/keys [uri db]
                     ::pass/keys [subject]
                     :as req}]
  (let [schema (some-> (xt/entity db uri) ::grab/schema)
        body (some-> req :juxt.site.alpha/received-representation :juxt.http.alpha/body (String.))

        _ (log/tracef "received-representation is %s" (pr-str (keys (some-> req :juxt.site.alpha/received-representation))))

        [document-str operation-name]
        (case (some-> req ::site/received-representation ::http/content-type)
          "application/json"
          (let [json (some-> body json/read-value)]
            [(get json "query") (get json "operationName")])

          "application/graphql"
          [body nil]

          (throw (ex-info (format "Unknown content type for GraphQL request: %s" (some-> req ::site/received-representation ::http/content-type)) req)))

        _ (when (nil? document-str)
            (throw (ex-info "Nil GraphQL query" (-> req
                                                    (update-in [::site/resource] dissoc ::grab/schema)
                                                    (dissoc :juxt.pass.alpha/request-context)))))
        document
        (try
          (let [document
                (try
                  (parser/parse document-str)
                  (catch Exception e
                    (throw (ex-info "Failed to parse document" {:errors [{:message (.getMessage e)}]}))))]
            (document/compile-document document schema))
          (catch Exception e
            (let [errors (:errors (ex-data e))]
              (throw
               (ex-info
                "Error parsing or compiling GraphQL query"
                (into
                 req
                 (cond-> {:ring.response/status 400}
                   (seq errors) (assoc ::errors errors)))
                e)))))

        ;; TODO: If JSON, get operationName and use it here
        results (query schema document operation-name db subject)

        ;; Map to application/json
        results (postwalk
                  (fn [x]
                    (cond-> x
                      (and (vector? x) (= :kind (first x)))
                      (update 1 (comp str/upper-case #(str/replace % "-" "_") name))))
                  results)]

    (-> req
        (assoc
         :ring.response/status 200
         :ring.response/body
         (json/write-value-as-string results)))))

(defn put-schema [crux-node resource schema-str]
  (let [schema (schema/compile-schema (parser/parse schema-str))]
    (xt/await-tx
     crux-node
     (xt/submit-tx
      crux-node
      [[:crux.tx/put (assoc resource ::grab/schema schema)]]))))

(defn put-handler [{::site/keys [uri db crux-node] :as req}]
  (let [schema-str (some-> req :juxt.site.alpha/received-representation :juxt.http.alpha/body (String.))

        _ (when-not schema-str
            (throw
             (ex-info
              "No schema in request"
              (into
               req
               {:ring.response/status 400}))))

        resource (xt/entity db uri)]

    (when (nil? resource)
      (throw (ex-info "GraphQL resource not configured" {:uri uri})))

    (try
      (put-schema crux-node resource schema-str)
      (assoc req :ring.response/status 204)
      (catch clojure.lang.ExceptionInfo e
        (let [errors (:errors (ex-data e))]
          (if (seq errors)
            (do
              (log/trace e "error")
              (log/tracef "schema errors: %s" (pr-str (:errors (ex-data e))))
              (throw
               (ex-info
                "Errors in schema"
                (into
                 req
                 (cond-> {:ring.response/status 400}
                   (seq errors) (assoc ::errors errors)))
                e)))
            (throw
             (ex-info
              "Failed to put schema"
              (into
               req
               {:ring.response/status 500})
              e))))))))

(defn plain-text-error-message [error]
  (str (:message error)
       " [" (->> (dissoc error :message)
                 sort
                 (map (fn [[k v]] (format "%s=%s" (name k) v)))
                 (str/join ", ")) "]"))

(defn put-error-text-body [req]
  (log/tracef "put-error-text-body: %d errors" (count (::errors req)))
  (cond
    (::errors req)
    (->>
     (for [error (::errors req)]
       (cond-> (str \tab (plain-text-error-message error))
         (:location error) (str " (line " (-> error :location :row inc) ")")))
     (into ["Schema compilation errors"])
     (str/join (System/lineSeparator)))
    (:ring.response/body req) (:ring.response/body req)
    :else "Unknown error, check stack trace"))

(defn put-error-json-body [req]
  (json/write-value-as-string
   {:message "Schema compilation errors"
    :errors (::errors req)}))

(defn post-error-text-body [req]
  (log/tracef "put-error-text-body: %d errors" (count (::errors req)))
  (->>
   (for [error (::errors req)]
     (cond-> (str \tab (:error error))
       (:location error) (str " (line " (-> error :location :row inc) ")")))
   (into ["Query errors"])
   (str/join (System/lineSeparator))))

(defn post-error-json-body [req]
  (json/write-value-as-string
   {:errors
    (for [error (::errors req)
          :let [location (:location error)]]
      (cond-> error
        location (assoc :location location)))}))

(defn stored-document-put-handler [{::site/keys [uri db crux-node] :as req}]
  (let [document-str (some-> req :juxt.site.alpha/received-representation :juxt.http.alpha/body (String.))

        _ (when-not document-str
            (throw
             (ex-info
              "No document in request"
              (into
               req
               {:ring.response/status 400}))))

        resource (xt/entity db uri)]

    (when (nil? resource)
      (throw (ex-info "GraphQL stored-document resource not configured" {:uri uri})))

    ;; Validate resource
    (when-not (::site/graphql-schema resource)
      (throw (ex-info "Resource should have a :juxt.site.alpha/graphql-schema key" {::site/resource resource})))

    (let [schema-id (::site/graphql-schema resource)
          schema (some-> db (xt/entity schema-id) :juxt.grab.alpha/schema)]

      (when-not schema
        (throw
         (ex-info
          "Cannot store a GraphQL document when the schema hasn't been added"
          {::site/graph-schema schema-id})))

      (try
        (let [document (document/compile-document
                        (parser/parse document-str)
                        schema)]
          (xt/await-tx
           crux-node
           (xt/submit-tx
            crux-node
            [[:crux.tx/put (assoc resource ::grab/document document)]])))

        (assoc req :ring.response/status 204)

        (catch clojure.lang.ExceptionInfo e
          (let [errors (:errors (ex-data e))]
            (if (seq errors)
              (do
                (log/tracef "GraphQL document errors: %s" (pr-str (:errors (ex-data e))))
                (throw
                 (ex-info
                  "Errors in GraphQL document"
                  (into
                   req
                   (cond-> {:ring.response/status 400}
                     (seq errors) (assoc ::errors errors)))
                  e)))
              (throw
               (ex-info
                "Failed to store GraphQL document due to error"
                (into
                 req
                 {:ring.response/status 500})
                e)))))))))


(defn stored-document-post-handler [_]
  (throw (ex-info "TODO" {})))

(defn text-plain-representation-body [{::site/keys [db] :as req}]
  (let [lookup (fn [id] (xt/entity db id))]
    (-> req ::site/selected-representation ::site/variant-of lookup ::http/body (String. "utf-8"))))
