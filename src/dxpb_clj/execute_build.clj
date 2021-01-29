(ns dxpb-clj.execute-build
  (:import java.util.Base64)
  (:require [config.core :refer [env]]
            [clojure.data.json :as json]
            [org.httpkit.client :as http]
            ))

;;; These take a pkgname, host and target arch, if that's cross, and a git hash
;;; They then return a map like so:
;;; {:result? <fn-of-0-arguments> ;; (nil instead of a fn if the thing isn't started)
;;;  ;; Returns a keyword, one of :done :working :failed
;;;  :build-profile-possible <bool> ;; if this is false, don't try scheduling builds
;;;  :__debug <arbitrary|optional> ;; don't rely on this being there. Ever.
;;;  }

(defn- submit-package-to-parameterized-nomad [& {:keys [pkgname host-arch target-arch is-cross git-hash]}]
  (let [payload {:Meta {:git_hash git-hash
                        :pkgname pkgname
                        :host_arch host-arch
                        :target_arch target-arch
                        :is_cross is-cross}}
        endpoint (str (:dxpb-nomad-endpoint env) "/v1/job/" (:dxpb-nomad-build-job env) "/dispatch")

        payload-json (json/write-str payload)
        env-http-config (get env :dxpb-nomad-http-config {})
        resp (->> {:body payload-json}
                  (merge env-http-config)
                  @(http/post endpoint)
                  :body
                  json/read-str)
        job-id (get resp "DispatchedJobID")
        status-endpoint (str (:dxpb-nomad-endpoint env) "/v1/job/" job-id)]
    {:result? (fn []
                (->
                  @(http/get status-endpoint env-http-config)
                  :body
                  json/read-str
                  (get "Status")
                  keyword))
     :build-profile-possible true
     :__debug resp}))

(defn get-executor [based-on]
  (case based-on
    :parameterized-nomad {:submit submit-package-to-parameterized-nomad}))
