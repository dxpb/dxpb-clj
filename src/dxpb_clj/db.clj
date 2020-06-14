(ns dxpb-clj.db
  (:require [crux.api :as crux]
            [clojure.java.io :as io]
            [config.core :refer [env]]
            [clojure.set :refer [union]]
            ))

(def node (atom nil))

(defn- only [in]
  (if (= 1 (count in))
    (first in)))

(defn start-standalone-node ^crux.api.ICruxAPI [storage-dir]
  (crux/start-node {:crux.node/topology '[crux.standalone/topology
                                          crux.kv.rocksdb/kv-store]
                    :crux.kv/db-dir (str (io/file storage-dir "rocksdb"))}))

(defn get-storage-dir! []
  (or (:DXPB_SERVER_DIR env)
      "./datadir"))

(defn db-guard []
  (if (nil? @node)
    (reset! node (start-standalone-node (get-storage-dir!)))))

(defn add-pkg [pkgname pkginfo]
  (db-guard)
  (let [make-map-data (fn [[archspec pkginfo]]
                        (let [hostarch (:XBPS_ARCH archspec)
                              targetarch (:XBPS_TARGET_ARCH archspec)
                              is-cross (get archspec :cross false)
                              key-of-info (keyword (str pkgname ":"
                                                        hostarch ":"
                                                        targetarch ":"
                                                        "cross:" is-cross))]
                          (merge pkginfo
                                 {:crux.db/id key-of-info}
                                 {:dxpb/hostarch hostarch}
                                 {:dxpb/targetarch targetarch}
                                 {:dxpb/crossbuild is-cross}
                                 {:pkgname pkgname}
                                 )))
        to-submit (map #(vector :crux.tx/put (make-map-data %))
                       pkginfo)]
    (crux/submit-tx @node to-submit)))

(defn does-pkgname-exist [pkgname]
  (db-guard)
  (not (empty?
         (crux/q (crux/db @node)
                 {:find '[?e]
                  :where '[[?e :pkgname ?name]]
                  :args [{'?name pkgname}]}))))

(defn list-of-all-pkgnames []
  (db-guard)
  (apply concat (crux/q (crux/db @node)
                        {:find '[?name]
                         :where '[[?e :pkgname ?name]]})))

(defn get-pkg-key [pkgname {:keys [XBPS_ARCH XBPS_TARGET_ARCH cross] :or {cross false} :as build-profile}]
  (db-guard)
  (crux/q (crux/db @node)
                 {:find '[?e]
                  :where '[[?e :pkgname ?name]
                           [?e :dxpb/hostarch ?hostarch]
                           [?e :dxpb/targetarch ?targetarch]
                           [?e :dxpb/crossbuild ?cross]
                           ]
                  :args [{'?name pkgname
                          '?hostarch XBPS_ARCH
                          '?targetarch XBPS_TARGET_ARCH
                          '?cross cross
                          }]})
    )

(defn get-pkg-data
  ([pkgkey]
   (db-guard)
   (crux/entity (crux/db @node) pkgkey))
  ([pkgname {:keys [XBPS_ARCH XBPS_TARGET_ARCH cross] :or {cross false} :as build-profile}]
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
                                    [?e :depends ?deps]
                                    [?e :dxpb/targetarch ?targetarch]
                                    ]
                           :args all-pkg-query-args})
          makedepends (crux/q (crux/db @node)
                              {:find '[?deps]
                               :where '[[?e :pkgname ?name]
                                        [?e :makedepends ?deps]
                                        [?e :dxpb/targetarch ?targetarch]
                                        ]
                               :args all-pkg-query-args})]

      (apply union (map set [(-> depends vec flatten) (-> makedepends vec flatten)])))))

(defn pkg-is-noarch [pkgname]
  (db-guard)
  (let [pkgname (if (keyword? pkgname) (name pkgname) pkgname)]
    (-> (crux/q (crux/db @node)
                {:find '[?archs]
                 :where '[[?e :pkgname ?name]
                          [?e :archs ?archs]
                          ]
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
                          [?e :version ?version]
                          ]
                 :args [{'?name pkgname}]})
        ;; We have a set of vectors of strings. Better be the same across all pkgs!
        only
        ;; We have a vector of strings, each vector is from 1 pkg, hope they all match!
        only
        )))
