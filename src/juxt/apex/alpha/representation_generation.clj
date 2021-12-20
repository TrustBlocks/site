;; Copyright © 2021, JUXT LTD.

(ns juxt.apex.alpha.representation-generation
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [juxt.site.alpha.response :as response]
   [clojure.walk :refer [postwalk]]
   [xtdb.api :as x]
   [hiccup.page :as hp]
   [json-html.core :refer [edn->html]]
   [jsonista.core :as json]
   [juxt.apex.alpha.parameters :as parameters]
   [juxt.pass.alpha.pdp :as pdp]
   [juxt.site.alpha.graphql :as graphql]
   [juxt.site.alpha.selmer :as selmer]
   [juxt.site.alpha.util :as util]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]
   [juxt.jinx.alpha :as jinx]))

(alias 'jinx (create-ns 'juxt.jinx.alpha))
(alias 'http (create-ns 'juxt.http.alpha))
(alias 'apex (create-ns 'juxt.apex.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))
(alias 'site (create-ns 'juxt.site.alpha))

(defn ->inject-params-into-query [input params req]

  ;; Replace input with values from params
  (postwalk
   (fn [x]
     (cond
       (and (map? x)
            (contains? x :name)
            (#{"query" "path"} (:in x "query")))
       (let [kw (keyword (:in x "query"))]
         (or
          (get-in params [kw (:name x) :value])
          (:default x)
          (get-in params [kw (:name x) :param "default"])))

       (and (map? x)
            (contains? x :juxt.site.alpha/ref))
       (get-in req (get x :juxt.site.alpha/ref))

       :else x))
   input))

;; By default we output the resource state, but there may be a better rendering
;; of collections, which can be inferred from the schema being an array and use
;; the items subschema.

;; This generates representations
(defn entity-bytes-generator [{::site/keys [uri resource selected-representation db]
                               ::pass/keys [authorization subject] :as req}]

  (assert authorization "No authorization to generate entity payload")

  (let [param-defs
        (get-in resource [::apex/operation "parameters"])

        db (x/with-tx db [[:xtdb.api/put
                           (-> subject
                               (assoc :xt/id :subject)
                               util/->freezeable)]])

        query-params (when-let [query-param-defs
                                (not-empty (filter #(= (get % "in") "query") param-defs))]
                       (parameters/process-query-string
                        (:ring.request/query req)
                        query-param-defs))

        query
        (some->
         (get-in resource [::apex/operation "responses" "200" "juxt.site.alpha/query"])
         (edn/read-string)
         (->inject-params-into-query {:path (::apex/openapi-path-params resource)
                                      :query query-params} req)
         (pdp/->authorized-query authorization))

        {singular-result? "juxt.site.alpha/singular-result?"
         extract-first-projection? "juxt.site.alpha/extract-first-projection?"
         extract-entry "juxt.site.alpha/extract-entry"}
        (get-in resource [::apex/operation "responses" "200"])

        resource-state
        (or
         (when query
           (try
             (cond->> (x/q db query)
               extract-first-projection? (map first)
               extract-entry (map #(get % extract-entry))
               singular-result? first)
             (catch Exception e
               (throw (ex-info "Failure during XTDB query" {:query query} e)))))

         ;; Try to get the resource's state via GraphQL
         (when-let [stored-query-resource-path (get-in resource [::apex/operation "x-juxt-site-graphql-query-resource"])]
           (let [stored-query-id (if (.startsWith stored-query-resource-path "/")
                                   (str (:juxt.site.alpha/base-uri req) stored-query-resource-path)
                                   stored-query-resource-path)
                 operation-name (or
                                 (get-in resource [::apex/operation "x-juxt-site-graphql-operation-name"])
                                 (get-in resource [::apex/operation "operationId"]))
                 variables
                 (into
                  (graphql/common-variables req)
                  (for [[k v] (::apex/openapi-path-params resource)
                        :when (::jinx/valid? v)]
                    [k (:value v)]))]
             (when operation-name
               (let [result (graphql/graphql-query
                             req
                             stored-query-id operation-name variables)]
                 ;; TODO: Need to check if the result indicates that nothing was found
                 ;; Perhaps the test should be if all values in the :data map are nil
                 (if (seq (:errors result))
                   (throw
                    (ex-info
                     "Getting the state for the resource via GraphQL resulted in erors"
                     (into req {:ring.response/status 500
                                ::errors (:errors result)
                                ::data (:data result)})))
                   (:data result))))))

         ;; The resource-state is the entity, if found. TODO: Might want to
         ;; filter out the http metadata?
         (x/entity db uri)

         ;; No resource-state, so there can be no representations, so 404.
         (throw
          (ex-info
           (str "No resource state from OpenAPI path: " (::apex/openapi-path req))
           (into req {:ring.response/status 404}))))]

    ;; Now we've found the resource state, we need to generate a representation
    ;; of it.

    (case (::http/content-type selected-representation)
      "application/json"
      ;; TODO: Might want to filter out the http metadata at some point
      (-> resource-state
          (json/write-value-as-string (json/object-mapper {:pretty true}))
          (str "\r\n")
          (.getBytes "UTF-8"))

      "text/html;charset=utf-8"
      (let [config (get-in resource [::apex/operation "responses"
                                     "200" "content"
                                     (::http/content-type selected-representation)])
            template (get config "x-juxt-site-template")]

        (cond
          template
          (selmer/render-file
           template resource-state db
           (get config "x-juxt-site-template-custom-resource-path")
           (response/xt-template-loader req db))

          :else
          (->
           (hp/html5
            [:h1 (get config "title" "No title")]

            [:pre (pr-str config)]

            ;; Get :path-params = {"id" "owners"}

            (cond
              (= (get config "type") "edn-table")
              (list
               [:style
                (slurp (io/resource "json.human.css"))]
               (edn->html resource-state))

              (= (get config "type") "table")
              (if (seq resource-state)
                (let [fields (distinct (concat [:xt/id]
                                               (keys (first resource-state))))]
                  [:table {:style "border: 1px solid #888; border-collapse: collapse;"}
                   [:thead
                    [:tr
                     (for [field fields]
                       [:th {:style "border: 1px solid #888; padding: 4pt; text-align: left"}
                        (pr-str field)])]]
                   [:tbody
                    (for [row resource-state]
                      [:tr
                       (for [field fields
                             :let [val (get row field)]]
                         [:td {:style "border: 1px solid #888; padding: 4pt; text-align: left"}
                          (cond
                            (uri? val)
                            [:a {:href val} val]
                            :else
                            (pr-str (get row field)))])])]])
                [:p "No results"])

              :else
              (let [fields (distinct (concat [:xt/id] (keys resource-state)))]
                [:dl
                 (for [field fields
                       :let [val (get resource-state field)]]
                   (list
                    [:dt
                     (pr-str field)]
                    [:dd
                     (cond
                       (uri? val)
                       [:a {:href val} val]
                       :else
                       (pr-str (get resource-state field)))]))]))

            [:h3 "Resource state"]
            [:pre (with-out-str (pprint resource-state))])
           (.getBytes "UTF-8")))))))
