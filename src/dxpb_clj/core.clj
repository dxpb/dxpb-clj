(ns dxpb-clj.core
  (:gen-class)
  (:import java.io.File)
  (:require [clojure.core.async :as async :refer [>!! <!! >! <! go chan thread pipeline-async close!]]
            [clojure.java.shell :as shell :refer [sh]]
            [clojure.string :refer [split trim]]
            [duratom.core :refer [duratom]]
            [clojure.java.io :refer [writer]]
            [clojure.pprint :refer [pprint]]))

;;(def ALL_PKGS (duratom :local-file
;;                       :file-path "./pkgs.edn"
;;                       :init {}))
(def ALL_PKGS (atom {}))

(def XBPS_SRC_WORKERS (atom 0))

(def ARCH_PAIRS [{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "x86_64"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "x86_64-musl"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "i686"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "armv6l"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "armv6l-musl"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "armv7l"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "armv7l-musl"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "aarch64"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "aarch64-musl"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc-musl"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc64"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc64-musl"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc64le"}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc64le-musl"}
                 ])

(defn graph-is-read []
  (= 0 @XBPS_SRC_WORKERS))

(defn sh-wrap [& {:keys [env dir cmd]}]
  (shell/with-sh-env env
    (shell/with-sh-dir
      dir
      (apply sh cmd))))

(defn parse-show-pkg-info [{:keys [show-pkg-info show-avail] :as full-info}]
  (let [new-info (merge {::err (not= 0 (:exit show-pkg-info))
                         ::can-be-built (= 0 (:exit show-avail))}
                        (apply merge
                               (->> (for [pkg-variable (split (:out show-pkg-info) #"\n\n")]
                                      (clojure.string/split pkg-variable #"\s+"))
                                    (filter #(> (count %) 1)) ;; is set
                                    (map #(hash-map (keyword (apply str (drop-last (first %)))) (rest %)))))
                        )
        version-str (str (first (:version new-info)) "_" (first (:revision new-info)))]
    (-> new-info
        (assoc :version version-str)
        (dissoc :revision)
        (dissoc :pkgname)
        )))

(defn parse-pkg-info [info]
  (let [new-info (apply merge (for [arch-info (-> info :raw-info)]
                                {(:arch-set arch-info)
                                 {:straight (parse-show-pkg-info (:straight arch-info))
                                  :cross (parse-show-pkg-info (:cross arch-info))
                                  } }))
        version (let [versions (set (apply concat (for [[_ info] new-info] (for [[_ info] info] (:version info)))))]
                  (if (= 1 (count versions))
                    (first versions)))
        ]
    {:pkgname (:pkgname info)
     :version version
     :info new-info
     }))

(defn xbps-src-read [path archs]
  (fn [pkgname result]
    (thread
      (let [output (parse-pkg-info
                     {:pkgname pkgname
                      :raw-info (for [arch archs]
                                  (let [tgt-arch (:XBPS_TARGET_ARCH arch)]
                                    {:arch-set arch
                                     :straight {:show-pkg-info (sh-wrap :env arch :dir path :cmd ["./xbps-src" "-q" "-i" "show-pkg-var-dump" pkgname])
                                                :show-avail (sh-wrap :env arch :dir path :cmd ["./xbps-src" "show-avail" pkgname])
                                                }
                                     :cross {:show-pkg-info (sh-wrap :env arch :dir path :cmd ["./xbps-src" "-a" tgt-arch "-q" "-i" "show-pkg-var-dump" pkgname])
                                             :show-avail (sh-wrap :env arch :dir path :cmd ["./xbps-src" "-a" tgt-arch "show-avail" pkgname])
                                             }
                                     }))})]
        (>!! result output))
      (close! result)
      )
    ))

(defn augment-all-pkgs [with-this]
  (let [pkgname (:pkgname with-this)]
    (swap! ALL_PKGS assoc (keyword pkgname) {:info (:info with-this)
                                             :version (:version with-this)})))

(defn chan-write-all [c in]
  (if in
    (do
      (async/put! c (first in) (fn [_] (chan-write-all c (next in)))))))

(defn bootstrap-pkg-list [path]
  (let [file-list (map #(.getName %) (.listFiles (File. (str path "/srcpkgs"))))
        total-num-files (count file-list)
        pkgnames (chan 1000)
        pkginfo (chan 1000)
        ]
    (pipeline-async 15 pkginfo (xbps-src-read path ARCH_PAIRS) pkgnames)
    (chan-write-all pkgnames file-list)
    (loop [done 0]
      (println done "/" total-num-files)
      (let [new-info (<!! pkginfo)]
        (augment-all-pkgs new-info))
      (if (not= (inc done) total-num-files)
        (recur (inc done)))
      )))

;; XXX: Write tests, testing pkgnames found in templates as written
;; Valid ones get a pkgname out. Invalid ones get a nil.
;; I don't like how this references @ALL_PKGS
(defn pkgname-to-key [pkgname]
  (let [take1 (keyword pkgname)]
    (if (>= (.indexOf (keys @ALL_PKGS) take1) 0)
      {:as-key take1 :version-specified false :spec pkgname}
      (if (or (>= (.indexOf pkgname ">") 0)
              (>= (.indexOf pkgname "<") 0)
              (>= (.indexOf pkgname "=") 0)
              )
        (let [{:keys [out exit]} (sh "xbps-uhelper" "getpkgdepname" pkgname)]
          (if (= 0 exit)
            {:as-key (keyword (trim out)) :version-specified true :spec pkgname}))
        (let [{:keys [out exit]} (sh "xbps-uhelper" "getpkgname" pkgname)]
          (if (= 0 exit)
            {:as-key (keyword (trim out)) :version-specified true :spec pkgname}))
        ))))

;; XXX: write spec: in must be list of strings or nil
;; out must be {:found LIST :unfindable LIST}
(defn obtain-pkgnames [list-in]
  (loop [pkgnames list-in
         found []
         unfindable []]
    (if pkgnames
      (if-let [{:keys [as-key version-specified spec]} (pkgname-to-key (first pkgnames))]
        (recur (next pkgnames) (conj found {as-key {:spec spec :version-checked (not version-specified)}}) unfindable)
        (recur (next pkgnames) found (conj unfindable (first pkgnames)))
        )
      {:found found
       :unfindable unfindable})))

(defn merge-obtained-pkgnames [a b]
  (cond
    (= (:spec a) (:spec b)) a ;; doesn't matter, a == b basically
    (:version-checked a) b
    (:version-checked b) a ;; by being here, a's version was not checked
    ;; by being down here, neither version was checked and specs not=
    (and (coll? (:spec a)) (coll? (:spec b))) {:spec (concat (:spec a) (:spec b)) :version-checked false}
    ;; but they are not both sequences... is either a sequence?
    (and (not (coll? (:spec a))) (not (coll? (:spec b)))) {:spec (vector (:spec a) (:spec b)) :version-checked false}
    ;; exactly 1 is a sequence
    (coll? (:spec a)) {:spec (conj (:spec a) (:spec b)) :version-checked false}
    (coll? (:spec b)) {:spec (conj (:spec b) (:spec a)) :version-checked false}
    ))

(defn pkgname-to-needs [& {:keys [pkgname all-pkgs build-env cross-build]}]
  (if-let [pkgname-as-specified (pkgname-to-key pkgname)]
    (let [pkgname (:as-key pkgname-as-specified)
          which-way (if cross-build :cross :straight)
          pkg (get-in all-pkgs [pkgname :info build-env which-way])
          {:keys [found unfindable]} (obtain-pkgnames (:hostmakedepends pkg))
          found-hostneeds (apply merge-with merge-obtained-pkgnames found) ;; found ;; nil is ironed out by obtain-pkgnames
          unfound-deps unfindable
          {:keys [found unfindable]} (obtain-pkgnames (concat (:makedepends pkg) (:depends pkg)))
          found-targetneeds (apply merge-with merge-obtained-pkgnames found) ;;found
          unfound-deps (concat unfindable unfound-deps)]
      {:host-requirements found-hostneeds
       :target-requirements found-targetneeds
       :unfindable unfound-deps
       }
      ))
  )

(defn write-all-pkgs-to-file [filename]
  (pprint @ALL_PKGS (writer filename)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
