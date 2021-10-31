(ns dxpb-clj.execute-build
  (:import java.util.Base64)
  (:require [config.core :refer [env]]
            [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [clojure.java.shell :as shell :refer [sh]]
            ))

;;; These take a pkgname, host and target arch, if that's cross, and a git hash
;;; They then return a map like so:
;;; {:result? <fn-of-0-arguments> ;; (nil instead of a fn if the thing isn't started)
;;;  ;; Returns a keyword, one of :pending :impossible :working :done :failed
;;;  :build-profile-possible <bool> ;; if this is false, don't try scheduling builds
;;;  :__debug <arbitrary|optional> ;; don't rely on this being there. Ever.
;;;  }

; unverified
(defn- submit-package-to-parameterized-nomad [& {:keys [pkgname pkgversion host-arch target-arch is-cross git-hash]}]
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

; need an object for the JVM to lock
; So long as this is the same object, the JVM locking mechanism will work.
; Uses the monitorenter and monitorexit bytecode instructions.
(def shell-lock (Object.))

(def accepted-host-archs (set (get env :dxpb-accepted-host-archs ["x86_64" "x86_64-musl"])))

(defn shell-build-pkg [{:keys [pkgname pkgversion host-arch target-arch is-cross git-hash]}]
  (let [build-env {:PKGNAME pkgname
                   :PKGVERSION pkgversion
                   :HOST_ARCH host-arch
                   :TARGET_ARCH target-arch
                   :CROSS_BUILD is-cross
                   :GIT_HASH git-hash
                   :HOSTDIR (:dxpb-binpkg-dir env)}
        dir (:dxpb-local-build-dir env)]
    (shell/with-sh-env build-env
      (shell/with-sh-dir dir
        (sh "dxpb-xbps-src")))))

(defn submit-package-to-shell [& {:keys [pkgname pkgversion host-arch target-arch is-cross git-hash] :as instruction}]
  (if (not (contains? accepted-host-archs host-arch))
    {:result? (fn []
                :failed)
     :build-profile-possible false
     :_debug {}}
    (let [building? (promise)
          build-result (future
                         (locking shell-lock
                           (deliver building? true)
                           (shell-build-pkg instruction)))]
      {:result? (fn []
                  (if (not (realized? building?))
                    :pending
                    (if (realized? build-result)
                      (case (:err @build-result)
                        0 :done
                        (case (:stdout @build-result)
                          "" :failed
                          :impossible))
                      :working)))
       :build-profile-possible true
       :__debug {}})))

#_ (def a (submit-package-to-shell :pkgname "abc" :host-arch "x86_64" :target-arch "x86_64" :is-cross false :git-hash "abcdefg"))
#_ (def b (submit-package-to-shell :pkgname "abcd" :host-arch "x86_64" :target-arch "x86_64" :is-cross true :git-hash "abcdefg"))
#_ ((:result? a))
#_ ((:result? b))

(defn get-executor [based-on]
  (case based-on
    :parameterized-nomad {:submit submit-package-to-parameterized-nomad}
    :basic-shell {:submit submit-package-to-shell}))
