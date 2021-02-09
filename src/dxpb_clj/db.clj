(ns dxpb-clj.db
  (:require [crux.api :as crux]
            [clojure.java.io :as io]
            [config.core :refer [env]]
            [clojure.set :refer [union]]
            ))

(def crux-fn-delete-package '(fn [ctx pkgname {:keys [XBPS_ARCH XBPS_TARGET_ARCH cross]}]
                               []))

(def crux-fn-ensure-pruned-subpkgs '(fn [ctx]
                                      []))

(def node (atom nil))

(defn- only [in]
  (when (= 1 (count in))
    (first in)))

(defn start-standalone-node ^crux.api.ICruxAPI [storage-dir]
  (let [mkdatadir #(str (io/file storage-dir %))]
    (crux/start-node {:crux/tx-log {:kv-store {:crux/module 'crux.rocksdb/->kv-store
                                               :db-dir (mkdatadir "tx-log")}}
                      :crux/document-store {:kv-store {:crux/module 'crux.rocksdb/->kv-store
                                                       :db-dir (mkdatadir "docs")}}
                      :crux/index-store {:kv-store {:crux/module 'crux.rocksdb/->kv-store
                                                    :db-dir (mkdatadir "indexes")}}
                      })))

(defn get-storage-dir! []
  (or (:DXPB_SERVER_DIR env)
      "./datadir"))

(defn ensure-db-functions [db]
  (when (not= (crux/entity (crux/db db) :delete-package)
              crux-fn-delete-package)
    (crux/submit-tx db {:crux.db/id :delete-package
                        :crux.db/fn crux-fn-delete-package}))
  (when (not= (crux/entity (crux/db db) :ensure-pruned-subpkgs)
              crux-fn-ensure-pruned-subpkgs)
    (crux/submit-tx db {:crux.db/id :ensure-pruned-subpkgs
                        :crux.db/fn crux-fn-ensure-pruned-subpkgs})))

(defn db-guard []
  (when (nil? @node)
    (reset! node (start-standalone-node (get-storage-dir!)))
    (ensure-db-functions @node)))

(defn take-instruction [instructions]
  (db-guard)
  (crux/submit-tx @node instructions))

(defn does-pkgname-exist [pkgname]
  (db-guard)
  (seq
    (crux/q (crux/db @node)
            {:find '[?e]
             :where '[[?e :pkgname ?name]
                      [?e :dxpb/type :package]]
             :args [{'?name pkgname}]})))

(defn list-of-all-pkgnames []
  (db-guard)
  (apply concat (crux/q (crux/db @node)
                        {:find '[?name]
                         :where '[[?e :pkgname ?name]
                                  [?e :dxpb/type :package]]})))

(defn list-of-bootstrap-pkgnames []
  (db-guard)
  (apply concat (crux/q (crux/db @node)
                        {:find '[?name]
                         :where '[[?e :pkgname ?name]
                                  [?e :dxpb/type :package]
                                  [?e :bootstrap ?ignored]]})))

(defn get-pkg-key [pkgname {:keys [XBPS_ARCH XBPS_TARGET_ARCH cross] :or {cross false}}]
  (db-guard)
  (crux/q (crux/db @node)
                 {:find '[?e]
                  :where '[[?e :pkgname ?name]
                           [?e :dxpb/type :package]
                           [?e :dxpb/hostarch ?hostarch]
                           [?e :dxpb/targetarch ?targetarch]
                           [?e :dxpb/crossbuild ?cross]]
                  :args [{'?name pkgname
                          '?hostarch XBPS_ARCH
                          '?targetarch XBPS_TARGET_ARCH
                          '?cross cross}]}))

(defn get-pkg-data
  ([pkgkey]
   (db-guard)
   (crux/entity (crux/db @node) pkgkey))
  ([pkgname build-profile]
   (-> (get-pkg-key pkgname build-profile)
       only
       only
       get-pkg-data)))

(defn get-all-needs-for-arch [& {:keys [pkgnames arch]}]
  (db-guard)
  (if (empty? pkgnames)
    #{}
    (let [all-pkg-query-args (vec (map #(hash-map '?name % '?targetarch arch) pkgnames))
          depends (crux/q (crux/db @node)
                          {:find '[?deps]
                           :where '[[?e :pkgname ?name]
                                    [?e :dxpb/type :package]
                                    [?e :depends ?deps]
                                    [?e :dxpb/targetarch ?targetarch]]
                           :args all-pkg-query-args})
          makedepends (crux/q (crux/db @node)
                              {:find '[?deps]
                               :where '[[?e :pkgname ?name]
                                        [?e :dxpb/type :package]
                                        [?e :makedepends ?deps]
                                        [?e :dxpb/targetarch ?targetarch]]
                               :args all-pkg-query-args})]
      (apply union (map set [(-> depends vec flatten) (-> makedepends vec flatten)])))))

(defn pkg-is-noarch [pkgname]
  (db-guard)
  (let [pkgname (if (keyword? pkgname) (name pkgname) pkgname)]
    (-> (crux/q (crux/db @node)
                {:find '[?archs]
                 :where '[[?e :pkgname ?name]
                          [?e :dxpb/type :package]
                          [?e :archs ?archs]]
                 :args [{'?name pkgname}]})
        ;; We have a set of vectors of lists
        only
        ;; We have a vector of lists, each vector is from 1 pkg
        only
        ;; We have a list of archs. It's not noarch if there are multiple values
        only
        ;; We have a string or nil.
        (= "noarch"))))

(defn pkg-version [pkgname]
  (db-guard)
  (let [pkgname (if (keyword? pkgname) (name pkgname) pkgname)]
    (-> (crux/q (crux/db @node)
                {:find '[?version]
                 :where '[[?e :pkgname ?name]
                          [?e :dxpb/type :package]
                          [?e :version ?version]]
                 :args [{'?name pkgname}]})
        ;; We have a set of vectors of strings. Better be the same across all pkgs!
        only
        ;; We have a vector of strings, each vector is from 1 pkg, hope they all match!
        only)))
