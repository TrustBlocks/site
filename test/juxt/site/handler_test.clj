;; Copyright © 2021, JUXT LTD.

(ns juxt.site.handler-test
  (:require
   [clojure.test :refer [deftest is are testing] :as t]
   [clojure.tools.logging :as log]
   [crux.api :as x]
   [jsonista.core :as json]
   [juxt.reap.alpha.encoders :refer [format-http-date]]
   [juxt.site.alpha.handler :as h]
   [juxt.mail.alpha.mail :as mailer])
  (:import
   (crux.api ICruxAPI)
   (java.io ByteArrayInputStream)))

(alias 'apex (create-ns 'juxt.apex.alpha))
(alias 'http (create-ns 'juxt.http.alpha))
(alias 'mail (create-ns 'juxt.mail.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))
(alias 'site (create-ns 'juxt.site.alpha))

(def ^:dynamic *opts* {})
(def ^:dynamic ^ICruxAPI *crux-node*)
(def ^:dynamic *handler*)
(def ^:dynamic *db*)

(defn with-crux [f]
  (with-open [node (x/start-node *opts*)]
    (binding [*crux-node* node]
      (f))))

(defn submit-and-await! [transactions]
  (->>
   (x/submit-tx *crux-node* transactions)
   (x/await-tx *crux-node*)))

(defn make-handler [opts]
  (-> h/handler
      (h/wrap-responder)
      (h/wrap-error-handling)
      (h/wrap-initialize-request opts)))

(defn with-handler [f]
  (binding [*handler* (make-handler
                       {::site/crux-node *crux-node*
                        ::site/base-uri "https://example.org"
                        ::site/uri-prefix "https://example.org"})]
    (f)))

(defn with-db [f]
  (binding [*db* (x/db *crux-node*)]
    (f)))

(t/use-fixtures :each with-crux with-handler)

(def access-all-areas
  {:crux.db/id "https://example.org/access-rule"
   ::site/description "A rule allowing access everything"
   ::site/type "Rule"
   ::pass/target '[]
   ::pass/effect ::pass/allow})

(def access-all-apis
  {:crux.db/id "https://example.org/access-rule"
   ::site/description "A rule allowing access to all APIs"
   ::site/type "Rule"
   ::pass/target '[[resource ::site/resource-provider :juxt.apex.alpha.openapi/openapi-path]]
   ::pass/effect ::pass/allow})

(deftest put-test
  (submit-and-await!
   [[:crux.tx/put access-all-apis]
    [:crux.tx/put
     {:crux.db/id "https://example.org/_site/apis/test/openapi.json"
      ::site/type "OpenAPI"
      :juxt.apex.alpha/openapi
      {"servers" [{"url" ""}]
       "paths"
       {"/things/foo"
        {"put"
         {"requestBody"
          {"content"
           {"application/json"
            {"schema"
             {"juxt.jinx.alpha/keyword-mappings"
              {"name" "a/name"}
              "properties"
              {"name" {"type" "string"
                       "minLength" 2}}}}}}}}}}}]])
  (let [body (json/write-value-as-string {"name" "foo"})
        _ (*handler*
           {:ring.request/method :put
            :ring.request/path "/things/foo"
            :ring.request/body (ByteArrayInputStream. (.getBytes body))
            :ring.request/headers
            {"content-length" (str (count body))
             "content-type" "application/json"}})
        db (x/db *crux-node*)]

    (is (= {:a/name "foo", :crux.db/id "https://example.org/things/foo"}
           (x/entity db "https://example.org/things/foo")))))

;; Evoke "Throwing Multiple API paths match"

(deftest two-path-parameter-path-preferred-test
  (submit-and-await!
   [[:crux.tx/put access-all-apis]
    [:crux.tx/put
     {:crux.db/id "https://example.org/_site/apis/test/openapi.json"
      ::site/type "OpenAPI"
      :juxt.apex.alpha/openapi
      {"servers" [{"url" ""}]
       "paths"
       {"/things/{a}"
        {"parameters"
         [{"name" "a" "in" "path" "required" "true"
           "schema" {"type" "string" "pattern" "\\p{Alnum}+"}}]
         "put"
         {"operationId" "putA"
          "requestBody"
          {"content"
           {"application/json"
            {"schema"
             {"properties"
              {"name" {"type" "string" "minLength" 1}}}}}}}}

        "/things/{a}/{b}"
        {"parameters"
         [{"name" "a" "in" "path" "required" "true"
           "schema" {"type" "string" "pattern" "\\p{Alnum}+"}}
          {"name" "b" "in" "path" "required" "true"
           "schema" {"type" "string" "pattern" "\\p{Alnum}+"}}]
         "put"
         {"operationId" "putAB"
          "requestBody"
          {"content"
           {"application/json"
            {"schema"
             {"properties"
              {"name" {"type" "string" "minLength" 1}}}}}}}}}}}]])
  (let [body (json/write-value-as-string {"name" "foo"})
        r (*handler*
           {:ring.request/method :put
            ;; Matches both {a} and {b}
            :ring.request/path "/things/foo/bar"
            :ring.request/body (ByteArrayInputStream. (.getBytes body))
            :ring.request/headers
            {"content-length" (str (count body))
             "content-type" "application/json"}})
        db (x/db *crux-node*)]
    (is (= "putAB"
           (get-in r [::site/resource ::site/request-locals ::apex/operation "operationId"])))))


(deftest inject-path-parameter-with-forward-slash-test
  ;; PUT a project code of ABC/DEF (with Swagger) and ensure the / is
  ;; preserved. This test tests an edge case where we want a path parameter to contain a /.
  (log/trace "")
  (submit-and-await!
   [[:crux.tx/put access-all-apis]
    [:crux.tx/put
     {:crux.db/id "https://example.org/_site/apis/test/openapi.json"
      ::site/type "OpenAPI"
      :juxt.apex.alpha/openapi
      {"servers" [{"url" ""}]
       "paths"
       {"/things/{a}"
        {"parameters"
         [{"name" "a" "in" "path" "required" "true"
           "schema" {"type" "string" "pattern" "\\p{Alnum}+"}
           "x-juxt-site-inject-property" "juxt/code"}]
         "put"
         {"operationId" "putA"
          "requestBody"
          {"content"
           {"application/json"
            {"schema"
             {"properties"
              {"name" {"type" "string" "minLength" 1}}}}}}}}

        "/things/{a}/{n}"
        {"parameters"
         [{"name" "a" "in" "path" "required" "true"
           "schema" {"type" "string" "pattern" "\\p{Alpha}+"}}
          {"name" "n" "in" "path" "required" "true"
           "schema" {"type" "string"}}]
         "put"
         {"operationId" "putAB"
          "requestBody"
          {"content"
           {"application/json"
            {"schema"
             {"properties"
              {"name" {"type" "string" "minLength" 1}}}}}}}}}}}]])

  (let [path (str"/things/" (java.net.URLEncoder/encode "ABC/DEF"))
        body (json/write-value-as-string {"name" "zip"})
        r (*handler*
           {:ring.request/method :put
            :ring.request/path path
            :ring.request/body (ByteArrayInputStream. (.getBytes body))
            :ring.request/headers
            {"content-length" (str (count body))
             "content-type" "application/json"}})
        db (x/db *crux-node*)]
    (is (= "/things/{a}" (get-in r [::site/resource :juxt.apex.alpha/openapi-path])))
    (is (= {:name "zip",
            :juxt/code "ABC/DEF",
            :crux.db/id "https://example.org/things/ABC%2FDEF"}
           (x/entity db (str "https://example.org" path))))))

(deftest triggers-test
  (log/trace "TESTING")
  (submit-and-await!
   [[:crux.tx/put access-all-apis]

    [:crux.tx/put {:crux.db/id "https://example.org/users/sue"
                   ::site/type "User"
                   ::site/description "Sue should receive an email on every alert"
                   ::email "sue@example.org"
                   ::email? true}]
    [:crux.tx/put {:crux.db/id "https://example.org/users/brian"
                   ::site/type "User"
                   ::site/description "Brian doesn't want emails"
                   ::email "brian@example.org"
                   ::email? false}]
    [:crux.tx/put {:crux.db/id "https://example.org/roles/service-manager"
                   ::site/type "Role"
                   ::site/description "A service manager"}]
    [:crux.tx/put {:crux.db/id "https://example.org/users/sue-is-a-service-manager"
                   ::site/type "UserRoleMapping"
                   ::user "https://example.org/users/sue"
                   ::role "https://example.org/roles/service-manager"}]
    [:crux.tx/put {:crux.db/id "https://example.org/users/brian-is-a-service-manager"
                   ::site/type "UserRoleMapping"
                   ::user "https://example.org/users/brian"
                   ::role "https://example.org/roles/service-manager"}]

    [:crux.tx/put
     {:crux.db/id "https://example.org/_site/apis/test/openapi.json"
      ::site/type "OpenAPI"
      :juxt.apex.alpha/openapi
      {"servers" [{"url" ""}]
       "paths"
       {"/alerts/{id}"
        {"put"
         {"requestBody"
          {"content"
           {"application/json"
            {"schema"
             {"properties"
              {"juxt.site.alpha/type" {"type" "string"}}}}}}}}}}}]

    [:crux.tx/put
     {:crux.db/id "https://example.org/templates/alert-notification.html"
      ::http/content "<h1>Alert</h1><p>There has been an alert. See {{ :href }}</p>"}]

    [:crux.tx/put
     {:crux.db/id "https://example.org/templates/alert-notification.txt"
      ::http/content "There has been an alert. See {{ :href }}"}]

    [:crux.tx/put
     {:crux.db/id "https://example.org/triggers/alert-notification"
      ::site/type "Trigger"
      ::site/query
      '{:find [email alert asset-type customer]
        :keys [juxt.mail.alpha/to href asset-type customer]
        :where [[request :ring.request/method :put]
                [request ::site/uri alert]
                [alert ::site/type "Alert"]
                [alert :asset-type asset-type]
                [alert :customer customer]

                ;; All service managers
                ;; TODO: Limit to alerts that are 'owned' by the same
                ;; dealer as the service manager
                [user ::site/type "User"]
                [user ::email email]
                [mapping ::role "https://example.org/roles/service-manager"]
                [mapping ::user user]]}

      ::site/action ::mail/send-emails
      ::mail/from "notifications@example.org"
      ::mail/subject "{{:asset-type}} Alert!"
      ::mail/html-template "https://example.org/templates/alert-notification.html"
      ::mail/text-template "https://example.org/templates/alert-notification.txt"}]])

  (let [path "/alerts/123"
        body (json/write-value-as-string
              {"id" "123"
               "juxt.site.alpha/type" "Alert"
               "state" "unprocessed"
               "asset-type" "Heart Monitor"
               "customer" "Mountain Ridge Hospital"})
        emails (atom [])]

    (with-redefs
      [mailer/send-mail!
       (fn [from to subject _ _]
         (swap! emails conj {:from from :to to :subject subject}))]

      (*handler*
       {:ring.request/method :put
        :ring.request/path path
        :ring.request/body (ByteArrayInputStream. (.getBytes body))
        :ring.request/protocol "HTTP/1.1"
        :ring.request/headers
        {"content-length" (str (count body))
         "content-type" "application/json"}}))

    (is (= "123" (:id (x/entity (x/db *crux-node*) "https://example.org/alerts/123"))))
    (is (= [{:from "notifications@example.org"
             :to "brian@example.org"
             :subject "Heart Monitor Alert!"}
            {:from "notifications@example.org"
             :to "sue@example.org"
             :subject "Heart Monitor Alert!"}] @emails))))

(deftest if-modified-since-test
  (submit-and-await!
   [[:crux.tx/put access-all-areas]
    [:crux.tx/put
     {:crux.db/id "https://example.org/test.png"
      ::http/last-modified #inst "2020-03-01"
      ::http/content-type "image/png"
      ::http/methods #{:get}}]])
  (are [if-modified-since status]
      (= status
         (:ring.response/status
          (*handler*
           {:ring.request/method :get
            :ring.request/headers
            (if if-modified-since
              {"if-modified-since"
               (format-http-date if-modified-since)}
              {})
            :ring.request/path "/test.png"})))
      nil 200
      #inst "2020-02-29" 200
      #inst "2020-03-01" 304
      #inst "2020-03-02" 304))

(deftest if-none-match-test
  (submit-and-await!
   [[:crux.tx/put access-all-areas]
    [:crux.tx/put
     {:crux.db/id "https://example.org/test.png"
      ::http/etag "\"abc\""
      ::http/content-type "image/png"
      ::http/methods #{:get :head :options}}]])
  (are [if-none-match status]
      (= status
         (:ring.response/status
          (*handler*
           {:ring.request/method :get
            :ring.request/headers
            (if if-none-match {"if-none-match" if-none-match} {})
            :ring.request/path "/test.png"})))
      nil 200
      "" 200
      "def" 200
      "abc" 200
      "\"abc\"" 304))

;; TODO: If-Unmodified-Since

;; 3.1: If-Match
(deftest if-match-wildcard-test
  (submit-and-await!
   [[:crux.tx/put access-all-areas]])
  (is (= 412
         (:ring.response/status
          (let [body "Hello"]
            (*handler*
             {:ring.request/method :put
              :ring.request/body (ByteArrayInputStream. (.getBytes body))
              :ring.request/headers
              {"content-length" (str (count body))
               "content-type" "application/json"
               "if-match" "*"}
              :ring.request/path "/test.png"}))))))

(defn if-match-run [if-match]
  (submit-and-await!
   [[:crux.tx/put access-all-areas]
    [:crux.tx/put
     {:crux.db/id "https://example.org/test.png"
      ::site/type "StaticRepresentation"
      ::http/etag "\"abc\""
      ::http/content-type "image/png"
      ::http/methods #{:put}
      }]])
  (:ring.response/status
   (let [body "Hello"]
     (*handler*
      {:ring.request/method :put
       :ring.request/body (ByteArrayInputStream. (.getBytes body))
       :ring.request/headers
       {"content-length" (str (count body))
        "content-type" "image/png"
        "if-match" if-match}
       :ring.request/path "/test.png"}))))

(deftest if-match-1-test
  (is (= 204 (if-match-run "\"abc\""))))

(deftest if-match-2-test
  (is (= 204 (if-match-run "\"abc\", \"def\""))))

(deftest if-match-3-test
  (is (= 412 (if-match-run "\"def\", \"ghi\""))))

#_((t/join-fixtures [with-crux with-handler])
 (fn []
   (submit-and-await!
    [[:crux.tx/put access-all-areas]
     [:crux.tx/put
      {:crux.db/id "https://example.org/test.png"
       ::site/type "StaticRepresentation"
       ::http/etag "\"abc\""
       ::http/content-type "image/png"
       ::http/methods #{:put}
       }]
     [:crux.tx/put
      {:crux.db/id "https://example.org/_site/tx_fns/put_if_match_etags"
       :crux.db/fn
       '(fn [ctx ;; uri ;;header-field new-rep if-match?
             ]
          (let [db (crux.api/db ctx)
                existing-representation (crux.api/entity db uri)]
            (juxt.site.alpha.handler/evaluate-preconditions! req)
            [[:crux.tx/put new-rep]]))

       :http/content-type "application/clojure"}]])

   (:ring.response/status
    (let [body "Hello"]
      (*handler*
       {:ring.request/method :put
        :ring.request/body (ByteArrayInputStream. (.getBytes body))
        :ring.request/headers
        {"content-length" (str (count body))
         "content-type" "image/png"
         "if-match" "\"ac\",\"def\""
         }
        :ring.request/path "/test.png"})))))

;; TODO:
      ;; "The server generating a 304 response MUST generate any of the following
      ;; header fields that would have been sent in a 200 (OK) response to the
      ;; same request: Cache-Control, Content-Location, Date, ETag, Expires, and
      ;; Vary." -- Section 4.1, RFC 7232

;; TODO: Call eval-conditional-requests on put/post

;; TODO: Try fix bug with DELETE (producing 415 not 204)

;; TODO: Error representations

;; TODO: eval-conditional-requests in a transaction function to avoid race-conditions

;; TODO: Security headers - read latest OWASP and similar
