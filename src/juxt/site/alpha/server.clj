;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.server
  (:require
   [integrant.core :as ig]
   [ring.adapter.jetty :refer [run-jetty]]
   [juxt.site.alpha.handler :refer [make-handler]]))

(alias 'site (create-ns 'juxt.site.alpha))


(defmethod ig/init-key ::server [_ {::site/keys [crux-node port host-map]}]
  (run-jetty (make-handler crux-node host-map) {:port port :join? false}))

(defmethod ig/halt-key! ::server [_ s]
  (when s
    (.stop s)))
