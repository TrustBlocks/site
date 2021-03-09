;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.init.extras
  (:require
   [clojure.java.io :as io]
   [crux.api :as x]
   [juxt.site.alpha.util :as util])
  (:import
   (java.io FileInputStream)
   (java.util Date)))

(alias 'http (create-ns 'juxt.http.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))
(alias 'site (create-ns 'juxt.site.alpha))

(defn put! [crux-node & ms]
  (->>
   (x/submit-tx
    crux-node
    (for [m ms]
      [:crux.tx/put m]))
   (x/await-tx crux-node)))

(defn file->representation [f]
  (let [nm (.getName f)
        [_ suffix] (re-matches #"[\p{Alnum}-]+\.([^\.]+).*" nm)
        body (.readAllBytes (FileInputStream. f))
        ct (get util/mime-types suffix)]
    {::http/body body
     ::http/content-type ct
     ::http/content-length (count body)
     ::http/last-modified (Date. (.lastModified f))
     ::http/etag (str \" (util/hexdigest body) \")}))

(defn put-login! [crux-node {::site/keys [canonical-host]}]
  (put!
   crux-node
   {:crux.db/id (str "https://" canonical-host "/_site/login")
    ::http/methods #{:get :head :options :post}
    ::http/content-type "text/html;charset=utf-8"
    ::http/content (slurp (io/resource "juxt/pass/alpha/login.html"))
    ;; The login page must have a classification of PUBLIC to be accessible.
    ::pass/classification "PUBLIC"
    ::http/acceptable "application/x-www-form-urlencoded"
    ::site/purpose ::site/post-login-credentials
    ::pass/expires-in (* 3600 24 30)}

   ;; Along with a special rule to allow anyone to POST to it.
   ;; TODO: Making the post handler PUBLIC should be OK
   {:crux.db/id (str "https://" canonical-host "/_site/rules/anyone-can-post-login-credentials")
    ::site/type "Rule"
    ::site/description "The login POST handler must be accessible by all"
    ::pass/target '[[request :ring.request/method #{:post}]
                    [resource ::site/purpose ::site/post-login-credentials]]
    ::pass/effect ::pass/allow}

   ;; We've got a login, we should have a logout too.
   {:crux.db/id (str "https://" canonical-host "/_site/logout")
    ::http/methods #{:post}
    ::http/acceptable "application/x-www-form-urlencoded"
    ::site/purpose ::site/logout}

   ;; This was intended to protect the name of the access token, but given this
   ;; is open source, it's not worth it.
   #_{:crux.db/id (str "https://" canonical-host "/_site/rules/those-logged-in-can-logout")
    ::site/type "Rule"
    ::site/description "The logout POST handler must be accessible by those logged in"
    ::pass/target '[[subject ::pass/username]
                    [resource ::site/purpose ::site/logout]]
      ::pass/effect ::pass/allow}))

(defn init-db-extras!
  "This adds a login form, styled using Tailwind. Not required if you provide your
  own login form. Also adds a Swagger UI."
  [crux-node opts]
  (put-login! crux-node opts))
