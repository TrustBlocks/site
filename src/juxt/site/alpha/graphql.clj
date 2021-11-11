;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.graphql
  (:require
   [ring.util.codec :refer [form-decode]]
   [juxt.grab.alpha.schema :as schema]
   [juxt.grab.alpha.document :as document]
   [jsonista.core :as json]
   [juxt.grab.alpha.execution :refer [execute-request]]
   [juxt.grab.alpha.parser :as parser]
   [clojure.string :as str]
   [clojure.set :refer [rename-keys]]
   [crux.api :as xt]
   [clojure.tools.logging :as log]
   [clojure.walk :refer [postwalk]]
   [clojure.edn :as edn]))

(alias 'site (create-ns 'juxt.site.alpha))
(alias 'http (create-ns 'juxt.http.alpha))
(alias 'grab (create-ns 'juxt.grab.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))
(alias 'g (create-ns 'juxt.grab.alpha.graphql))

(def default-query '{:find [e]
                     :in [[type]]
                     :where [[e :juxt.site/type type]]})

(defn assoc-some
  "Associates a key with a value in a map, if and only if the value is not nil."
  ([m k v]
   (if (or (nil? v) (false? v)) m (assoc m k v)))
  ([m k v & kvs]
   (reduce (fn [m [k v]] (assoc-some m k v))
           (assoc-some m k v)
           (partition 2 kvs))))

(defn to-xt-query [args values]
  (let [query (rename-keys
               (or (get args "q") default-query)
               ;; probably should do camelcase to kabab
               {:order :order-by})
        result
        (postwalk
         (fn [x]
           (cond-> x
             (and (map? x) (:keyword x))
             (-> (get :keyword) keyword)
             (and (map? x) (:set x))
             (-> (get :set) set)
             (and (map? x) (:edn x))
             (-> (get :edn) edn/read-string)))
         query)
        #_#_order (try
                (edn/read-string (get values "orderBy"))
                (catch Exception e
                  (prn "Invalid order, must be edn readable"
                       {:input (get values "orderBy")})))
        limit (get values "limit")
        offset (get values "offset")
        search-terms (get values "searchTerms")
        search-where-clauses
        (and (every? seq (vals search-terms))
             (apply concat
                    (for [[key val] search-terms
                          :let [key-symbol (keyword key)
                                val (str/join " "
                                              (map (fn [s] (str s "*"))
                                                   (str/split val #" ")))]]
                      [[`(~(symbol "text-search")
                          ~key-symbol
                          ~val)
                        '[[e v s]]]])))
        result (assoc-some
                result
                :find (and search-terms '[e v s])
                :order-by (and search-terms '[[s :desc]])
                :where (and search-where-clauses
                            (vec (concat (:where result) search-where-clauses)))
                ;; xt does limit before search which means we can't limit or
                ;; offset if we're also trying to search....
                :limit limit
                :offset (when (pos-int? offset)
                          offset))]
    result))

(defn generate-value [{:keys [type pathPrefix]}]
  (when type
    (str pathPrefix (java.util.UUID/randomUUID))))

(defn args-to-entity [args field]
  (reduce
   (fn [acc arg-def]
     (let [generator-args (get-in arg-def [::schema/directives-by-name "site" ::g/arguments "gen"])
           kw (get-in arg-def [::schema/directives-by-name "site" ::g/arguments "a"])]
       (cond
         (::g/name arg-def) ; is it a singular (not a NON_NULL or LIST)
         (let [val (or (get args (::g/name arg-def))
                       (generate-value generator-args))
               ;; Change a symbol value into a string

               ;; We don't want symbols in XT entities, because this leaks the
               ;; form-plane into the data-plane!
               val (cond-> val (symbol? val) str)]
           (cond-> acc
             (some? val) (assoc (keyword (or kw (::g/name arg-def))) val)))
         :else (throw (ex-info "Unsupported arg-def" {:arg-def arg-def})))))
   {}
   (::g/arguments-definition field)))

(defn process-xt-results
  [field results]
  (if (-> field ::g/type-ref ::g/list-type)
    results
    ;; If this isn't a list type, take the first
    (first results)))

(defn protected-lookup [e subject db]
  (let [lookup #(xt/entity db %)
        ent (lookup e)]
    (if-let [ent-ns (::pass/namespace ent)]
      (let [rules (some-> ent-ns lookup ::pass/rules)
            acls (->>
                  (xt/q
                   db
                   {:find ['(pull ?acl [*])]
                    :where '[[?acl ::pass/type "ACL"]
                             (check ?acl ?subject ?e)]
                    :rules rules
                    :in '[?subject ?e]}
                   subject e)
                  (map first))]
        (when (seq acls)
          ;; TODO: Also use the ACL to infer when/whether to select-keys
          ;;(select-keys ent (apply concat (map :keys acls)))
          ent
          ))

      ;; Return unprotected ent
      ent)))

(defn limit-results
  "Needs to be done when we can't use xt's built in limit/offset"
  [args results]
  (let [result-count (count results)
        limit (get args "limit" result-count)]
    (if (or (get args "searchTerms") (> result-count limit))
      (take limit (drop (get args "offset" 0) results))
      results)))

(defn pull-entities
  [db subject results query]
  (for [[e _ score?] results]
    (assoc-some (protected-lookup e subject db)
                :luceneScore (and (number? score?) score?)
                :xtQuery (pr-str query))))

(defn infer-query
  [db subject field query args]
  (let [type (-> field
                 ::g/type-ref
                 ::g/list-type
                 ::g/name)
        results (pull-entities db (xt/q db query [type]) query)]
    (or (process-xt-results field results)
        (throw (ex-info "No resolver found for " type)))))

(defn traverse [object-value atts subject db]
  (if (seq atts)
    (traverse (get
               (if (string? object-value)
                 (protected-lookup object-value subject db)
                 object-value)
               (keyword (first atts)))
              (rest atts)
              subject db)
    object-value))

(defn query [schema document operation-name variable-values crux-node db subject]
  (execute-request
   {:schema schema
    :document document
    :operation-name operation-name
    :variable-values variable-values
    :abstract-type-resolver
    (fn [{:keys [object-value]}]
      (:juxt.site/type object-value))
    :field-resolver
    (fn [{:keys [object-type object-value field-name argument-values] :as field-resolver-args}]

      (let [types-by-name (::schema/types-by-name schema)
            field (get-in object-type [::schema/fields-by-name field-name])
            site-args (get-in field [::schema/directives-by-name "site" ::g/arguments])
            field-kind (-> field ::g/type-ref ::g/name types-by-name ::g/kind)
            mutation? (=
                       (get-in schema [::schema/root-operation-type-names :mutation])
                       (::g/name object-type))]

        (cond
          mutation?
          (let [object-to-put (args-to-entity argument-values field)]
            (xt/await-tx
             crux-node
             (xt/submit-tx
              crux-node
              [[:crux.tx/put object-to-put]]))
            object-to-put)

          ;; Direct lookup - useful query roots
          (get site-args "e")
          (let [e (get site-args "e")]
            (protected-lookup e subject db))

          (get site-args "q")
          (let [object-id (:crux.db/id object-value)
                arg-keys (fn [m] (remove #{"limit" "offset" "orderBy"} (keys m)))
                q (assoc
                   (to-xt-query site-args argument-values)
                   :in (vec (cond->> (map symbol (arg-keys argument-values))
                              object-id (concat ['object]))))
                query-args (cond->> (vals argument-values)
                             object-id (concat [object-id]))
                results
                (try
                  (xt/q db q (first query-args))
                  (catch Exception e
                    (throw (ex-info "Failure when running XTDB query"
                                    {:message (ex-message e)
                                     :query q
                                     :args query-args}
                                    e))))
                limited-results (limit-results argument-values results)
                result-entities (pull-entities db subject limited-results q)]
            (log/tracef "GraphQL results is %s" result-entities)
            (process-xt-results field result-entities))

          (get site-args "a")
          (let [att (get site-args "a")
                val (if (vector? att)
                      (traverse object-value att subject db)
                      (get object-value (keyword att)))]
            (if (= field-kind 'OBJECT)
              (protected-lookup val subject db)
              val))

          ;; The use of a resolver should be a privileged operation, since it
          ;; has the potential to bypass access control.
          (get site-args "resolver")
          (let [resolver (requiring-resolve (symbol (get site-args "resolver")))]
            ;; Resolvers need to do their own access control
            (resolver (assoc field-resolver-args ::pass/subject subject :db db)))

          ;; Another strategy is to see if the field indexes the
          ;; object-value. This strategy allows for delays to be used to prevent
          ;; computing field values that aren't resolved.
          (contains? object-value field-name)
          (let [f (force (get object-value field-name))]
            (if (fn? f) (f argument-values) f))

          ;; If the key is 'id', we assume it should be translated to xt/id
          (= "id" field-name)
          (get object-value :crux.db/id)

          ;; Or simply try to extract the keyword
          (contains? object-value (keyword field-name))
          (let [result (get object-value (keyword field-name))]
            (if (-> field ::g/type-ref ::g/list-type)
              (limit-results argument-values result)
              result))

          (-> field ::g/type-ref ::g/list-type ::g/name)
          (infer-query db
                       subject
                       field
                       (to-xt-query site-args argument-values)
                       argument-values)

          (get argument-values "id")
          (xt/entity db (get argument-values "id"))

          (and (get site-args "aggregate")
               (get site-args "type"))
          (case (get site-args "aggregate")
            "count" (count (xt/q db (to-xt-query site-args argument-values) [(get site-args "type")])))

          :else
          (or (get site-args "defaultValue") ""))))}))

(defn post-handler [{::site/keys [uri crux-node db]
                     ::pass/keys [subject]
                     :as req}]

  (let [schema (some-> (xt/entity db uri) ::grab/schema)
        body (some-> req :juxt.site.alpha/received-representation :juxt.http.alpha/body (String.))

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
          (parser/parse document-str)
          (catch Exception e
            (log/error e "Error parsing GraphQL query")
            (throw (ex-info "Failed to parse document" {:errors [{:message (.getMessage e)}]}))))

        compiled-document
        (try
          (document/compile-document document schema)
          (catch Exception e
            (log/error e "Error parsing or compiling GraphQL query")
            (let [errors (:errors (ex-data e))]
              (log/errorf "Errors %s" (pr-str errors))
              (throw
               (ex-info
                "Error parsing or compiling GraphQL query"
                (into
                 req
                 (cond-> {:ring.response/status 400}
                   (seq errors) (assoc ::errors errors)))
                e)))))

        results (query schema compiled-document operation-name {} crux-node db subject)]

    (-> req
        (assoc
         :ring.response/status 200
         :ring.response/body
         (json/write-value-as-string results))
        (update :ring.response/headers assoc "content-type" "application/json"))))

(defn schema-resource [resource schema-str]
  (let [schema (schema/compile-schema (parser/parse schema-str))]
    (assoc resource ::grab/schema schema ::http/body (.getBytes schema-str))))

(defn put-schema [crux-node resource schema-str]
  (xt/await-tx
   crux-node
   (xt/submit-tx
    crux-node
    [[:crux.tx/put (schema-resource resource schema-str)]])))

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
      (catch Exception e
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
  (let [line (some-> error :location :line inc)]
    (str
     (when line (format "%4d: " line))
     (:message error)
     " [" (->> (dissoc error :message)
               sort
               (map (fn [[k v]] (format "%s=%s" (name k) v)))
               (str/join ", ")) "]")))

(defn put-error-text-body [req]
  (cond
    (::errors req)
    (->>
     (for [error (::errors req)]
       (cond-> (str \tab (plain-text-error-message error))
         ;;(:location error) (str " (line " (-> error :location :line) ")")
         ))
     (into ["Schema compilation errors"])
     (str/join (System/lineSeparator)))
    (:ring.response/body req) (:ring.response/body req)
    :else "Unknown error, check stack trace"))

(defn put-error-json-body [req]
  (json/write-value-as-string
   {:message "Schema compilation errors"
    :errors (::errors req)}))

(defn post-error-text-body [req]
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

(defn stored-document-post-handler
  [{::site/keys [crux-node db resource received-representation]
    ::pass/keys [subject]
    :as req}]

  (assert (.startsWith (::http/content-type received-representation)
                       "application/x-www-form-urlencoded"))

  ;; Look up GraphQL document from resource
  (let [posted-body (slurp (::http/body received-representation))
        form (form-decode posted-body)
        operation-name (get form "operationName")
        schema-id (::site/graphql-schema resource)
        schema (some-> (when schema-id (xt/entity db schema-id)) ::grab/schema)
        ;; TODO: This should be pre-parsed against schema
        document-str (String. (::http/body resource) "UTF-8")
        document (document/compile-document (parser/parse document-str) schema)
        results (query schema document operation-name
                       {}
                       nil ;; for crux-node, so we prevent get updates
                       db
                       subject)]
    (-> req
        (assoc-in [:ring.response/headers "content-type"] "application/json")
        (assoc :ring.response/body (json/write-value-as-bytes results)))))

(defn text-plain-representation-body [{::site/keys [db] :as req}]
  (let [lookup (fn [id] (xt/entity db id))]
    (-> req ::site/selected-representation ::site/variant-of lookup ::http/body (String. "utf-8"))))

(defn text-html-template-model [{::site/keys [resource db]}]
  (let [original-resource (if-let [variant-of (::site/variant-of resource)] (xt/entity db variant-of) resource)
        endpoint (:juxt.site.alpha/graphql-schema original-resource)
        schema-resource (xt/entity db endpoint)
        schema (some-> schema-resource ::grab/schema)
        schema-str (String. (some-> schema-resource ::http/body))
        document-str (String. (::http/body original-resource) "UTF-8")
        document (document/compile-document (parser/parse document-str) schema)
        operation-names (->> (:juxt.grab.alpha.document/operations document)
                             (filter #(= (::g/operation-type %) :query))
                             (map ::g/name))]
    {"document" (String. (::http/body original-resource) "UTF-8")
     "endpoint" endpoint
     "operationNames" operation-names
     "schemaString" schema-str
     "form" {"action" (:crux.db/id original-resource)}}))
