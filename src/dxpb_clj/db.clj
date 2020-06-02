(ns dxpb-clj.db
  (:require [crux.api :as crux]
            [clojure.java.io :as io]
            [config.core :refer [env]]
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
                                 )))]
    (crux/submit-tx @node
                       (map
                         #(vector :crux.tx/put (make-map-data %))
                         pkginfo))))

(defn does-pkgname-exist [pkgname]
  (db-guard)
  (not (empty?
         (crux/q (crux/db @node)
                 {:find '[?e]
                  :where '[[?e :pkgname ?name]]
                  :args [{'?name pkgname}]}))))

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
