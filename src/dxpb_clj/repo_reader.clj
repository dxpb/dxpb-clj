(ns dxpb-clj.repo-reader
  (:require [config.core :refer [env]]
            [clojure.java.io :as io]
            [clojure.string :refer [starts-with? ends-with? trim-newline]]
            [clojure.java.shell :as shell :refer [sh with-sh-env]]))

(defn- pkgspec->filename [{:keys [pkgname version arch]}]
  (str pkgname "-" version "." arch ".xbps"))

(defn- package-in-repo-from-file-directory [& {:keys [pkgname arch exact-version] :or {exact-version true} :as pkgspec}]
  (if exact-version
    (->> pkgspec
         pkgspec->filename
         (str (:dxpb-binpkg-dir env) "/")
         io/file
         .exists)
    (->> (:dxpb-binpkg-dir env)
         str
         io/file
         .list
         (filter #(and (starts-with? % (str pkgname "-")) (ends-with? % (str "." arch ".xbps"))))
         seq)))

(defn- package-in-repo-from-xbps-repodata [& {:keys [pkgname version arch exact-version] :or {exact-version true}}]
  (let [repodata-dir (or (:dxpb-repodata-dir env) (:dxpb-binpkg-dir env))
        {:keys [exit out err]} (with-sh-env {:XBPS_ARCH arch}
                                 (sh "xbps-query"
                                     "-i"
                                     (str "--repository=" repodata-dir)
                                     "-S"
                                     pkgname
                                     "--property"
                                     "pkgver"))
        pkg-present (zero? exit)]
    (when (seq err) ;; can be an empty string and not worth printing
      (prn err))
    (when pkg-present
      (if exact-version
        (= (trim-newline out) (str pkgname "-" version))
        true))))

(defn get-repo-reader [based-on]
  (case based-on
    :file {:package-in-repo? package-in-repo-from-file-directory}
    :repodata {:package-in-repo? package-in-repo-from-xbps-repodata}
    :file+repodata {:package-in-repo? (comp
                                        (partial apply
                                                 (every-pred some?
                                                             (complement false?)))
                                        (juxt package-in-repo-from-file-directory
                                              package-in-repo-from-xbps-repodata))}))
