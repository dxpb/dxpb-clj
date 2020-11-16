(ns dxpb-clj.core
  (:gen-class)
  (:import java.io.File)
  (:require [clojure.core.async :as async :refer [>!! <!! >! <! go chan thread pipeline-async close!]]
            [clojure.java.shell :as shell :refer [sh]]
            [clojure.string :refer [split trim]]
            [clojure.java.io :refer [writer]]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [compojure.core :as comp]
            [compojure.route :as comproute]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :as rd]
            [ring.middleware.multipart-params :as mp]
            [ring.util.response :as ring-response]
            [hiccup.core :as hiccup]
            [hiccup.page :as hiccup-page]
            [clojure.java.io :as io]
            [dxpb-clj.db :refer [add-pkg does-pkgname-exist get-pkg-data get-pkg-key get-all-needs-for-arch pkg-is-noarch pkg-version list-of-all-pkgnames list-of-bootstrap-pkgnames]]
            [dxpb-clj.repo-reader :refer [get-repo-reader]]))

(def REPO_READER (atom nil))

(defn repo-reader [& {:keys [force] :or {force false}}]
  (if (or (nil? @REPO_READER) force)
    (reset! REPO_READER (get-repo-reader :file+repodata))
    @REPO_READER))

(def XBPS_SRC_WORKERS (atom 0))

(def ARCH_PAIRS [{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "x86_64"         :cross false}
                 {:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "x86_64-musl"    :cross false}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "i686"           :cross false}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "armv6l"         :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "armv6l-musl"    :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "armv7l"         :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "armv7l-musl"    :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "aarch64"        :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "aarch64-musl"   :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc"            :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc-musl"       :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc64"          :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc64-musl"     :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc64le"        :cross true}
               ;;{:XBPS_ARCH "x86_64"       :XBPS_TARGET_ARCH "ppc64le-musl"   :cross true}
                 ])

(defn graph-is-read []
  (= 0 @XBPS_SRC_WORKERS))

(defn sh-wrap [& {:keys [env dir cmd]}]
  (shell/with-sh-env env
    (shell/with-sh-dir
      dir
      (apply sh cmd))))

(defn parse-show-pkg-info [{:keys [show-pkg-info show-avail]}]
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
        )))

(defn parse-pkg-info [info]
  (let [new-info (apply merge (for [arch-info (-> info :raw-info)]
                                {(:arch-set arch-info)
                                 (merge
                                   (parse-show-pkg-info arch-info)
                                   {:pkgname (:pkgname info)})})) ;; this is wrong for our uses for subpkgs, so we overwrite pkgname
        version (let [versions (set (apply concat (for [[_ info] new-info] (for [[_ info] info] (:version info)))))]
                  (if (= 1 (count versions))
                    (first versions)))
        ]
    {:pkgname (:pkgname info)
     :version version
     :info new-info }))

(defn xbps-src-read [path archs]
  (fn [pkgname result]
    (thread
      (let [output (parse-pkg-info
                     {:pkgname pkgname
                      :raw-info (for [arch archs]
                                  (let [tgt-arch (:XBPS_TARGET_ARCH arch)
                                        straight-build (not (:cross arch))]
                                    (merge
                                      {:arch-set arch}
                                      (if straight-build
                                        {:show-pkg-info (sh-wrap :env arch :dir path :cmd ["./xbps-src" "-q" "-i" "show-pkg-var-dump" pkgname])
                                         :show-avail (sh-wrap :env arch :dir path :cmd ["./xbps-src" "show-avail" pkgname])
                                         }
                                        {:show-pkg-info (sh-wrap :env arch :dir path :cmd ["./xbps-src" "-a" tgt-arch "-q" "-i" "show-pkg-var-dump" pkgname])
                                         :show-avail (sh-wrap :env arch :dir path :cmd ["./xbps-src" "-a" tgt-arch "show-avail" pkgname])
                                         }))
                                     ))})]
        (>!! result output))
      (close! result)
      )
    ))

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
        (add-pkg (:pkgname new-info) (:info new-info)))
      (if (not= (inc done) total-num-files)
        (recur (inc done)))
      )))

;; XXX: Write tests, testing pkgnames found in templates as written
;; Valid ones get a pkgname out. Invalid ones get a nil.
(defn pkgname-to-key [pkgname]
  (assert pkgname)
  (let [take1 (keyword pkgname)]
    (if (does-pkgname-exist pkgname)
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

;; XXX: write spec: in must be seq of strings or nil
;; out must be {:found LIST :unfindable LIST}
(defn obtain-pkgnames [list-in arch]
  (if (empty? list-in)
    {:found []
     :unfindable []}
    (loop [pkgnames (list* list-in)
           found []
           unfindable []]
      (if pkgnames
        (if-let [{:keys [as-key version-specified spec]} (pkgname-to-key (first pkgnames))]
          (recur (next pkgnames) (conj found {as-key {:spec spec :version-not-specified (not version-specified) :arch (if (pkg-is-noarch as-key) "noarch" arch)}}) unfindable)
          (recur (next pkgnames) found (conj unfindable (first pkgnames)))
          )
        {:found found
         :unfindable unfindable}))))

;; Takes in two maps, {:keys [spec version-not-specified arch]}, returns 1 map same pattern.
(defn merge-obtained-pkgnames [a b]
  (cond
    (= (:spec a) (:spec b)) a ;; doesn't matter, a == b basically
    (:version-not-specified a) b
    (:version-not-specified b) a ;; by being here, a's version was not checked
    ;; by being down here, neither version was checked and specs not=. This means any spec that becomes a coll is itself specifying a pkg version as well.
    (and (coll? (:spec a)) (coll? (:spec b))) {:spec (concat (:spec a) (:spec b)) :version-not-specified false :arch (:arch a)}
    ;; but they are not both sequences... is either a sequence?
    (and (not (coll? (:spec a))) (not (coll? (:spec b)))) {:spec (vector (:spec a) (:spec b)) :version-not-specified false :arch (:arch a)}
    ;; exactly 1 is a sequence
    (coll? (:spec a)) {:spec (conj (:spec a) (:spec b)) :version-not-specified false :arch (:arch a)}
    (coll? (:spec b)) {:spec (conj (:spec b) (:spec a)) :version-not-specified false :arch (:arch a)}
    ))

(defn obtain-all-deps-of-pkgs-for-arch [& {:keys [pkgnames arch] :as what}]
  (loop [next-specs (set pkgnames)
         seen-specs #{}]
    (if (not (empty? next-specs))
      (let [unseen-specs (set/difference next-specs seen-specs)]
        (recur (get-all-needs-for-arch :pkgnames unseen-specs :arch arch) (set/union unseen-specs seen-specs)))
      (obtain-pkgnames seen-specs arch))
    ))

(defn pkgname-to-needs [& {:keys [pkgname build-env]}]
  (if-let [pkgname-as-specified (pkgname-to-key pkgname)]
    (let [pkgname (:as-key pkgname-as-specified)
          pkg (get-pkg-data (name pkgname) build-env)
          {:keys [found unfindable]} (obtain-all-deps-of-pkgs-for-arch :pkgnames (:hostmakedepends pkg) :arch (:XBPS_ARCH build-env))
          found-hostneeds (apply merge-with merge-obtained-pkgnames found) ;; found ;; nil is ironed out by obtain-pkgnames
          unfound-deps unfindable
          {:keys [found unfindable]} (obtain-all-deps-of-pkgs-for-arch :pkgnames (concat (:makedepends pkg) (:depends pkg)) :arch (:XBPS_TARGET_ARCH build-env))
          found-targetneeds (apply merge-with merge-obtained-pkgnames found) ;;found
          unfound-deps (concat unfindable unfound-deps)]
      {:host-requirements found-hostneeds
       :target-requirements found-targetneeds
       :unfindable unfound-deps
       }
      ))
  )

;; Pure function. Can be memoized.
(defn verify-pkg-version-ok [& {:keys [pkgname version specs spec]}]
  (if (nil? spec)
    (if (coll? specs)
      (empty? (filter false? (map #(verify-pkg-version-ok :pkgname pkgname :version version :spec %) specs)))
      (verify-pkg-version-ok :pkgname pkgname :version version :spec specs)
      )
    (let [;; when running the below command, (= exit 1) is a match.
          our-pkg-spec (str pkgname "-" version)
          {:keys [exit]} (sh "xbps-uhelper" "pkgmatch" our-pkg-spec spec)
          ]
      (= exit 1)
      ))
  )

(defn need-to-filename [[pkgname-as-key need]]
  (let [pkgname (name pkgname-as-key)
        version (pkg-version pkgname)
        arch (:arch need)
        needs-verification (not (:version-not-specified need))
        specs-ok (if needs-verification
                   (verify-pkg-version-ok :pkgname pkgname
                                          :version version
                                          :specs (:spec need))
                   true)
        specs-ok (if (true? specs-ok)
                   specs-ok
                   (:spec need))
        ]
    {(str pkgname "-" version "." arch ".xbps") specs-ok}))

(defn need-to-pkg-availability [[pkgname-as-key need]]
  (let [pkgname (name pkgname-as-key)
        version (pkg-version pkgname)
        arch (:arch need)
        needs-verification (not (:version-not-specified need))
        specs-ok (if needs-verification
                   (verify-pkg-version-ok :pkgname pkgname
                                          :version version
                                          :specs (:spec need))
                   true)
        pkg-in-repo ((:package-in-repo? (repo-reader))
                     :pkgname pkgname
                     :version version
                     :arch arch)
        ]
    {:pkgname pkgname
     :version version
     :arch arch
     :in-repo pkg-in-repo
     :spec-ok specs-ok
     :spec (:spec need)}))

(defn pkg-requires-to-build [& {:keys [pkgname build-env] :as pkg-spec}]
  (let [spec-not-parsable (fn [known-data] (not (true? (val known-data))))
        {:keys [target-requirements host-requirements unfindable] :as needs} (pkgname-to-needs :pkgname pkgname :build-env build-env)
        host-packages-needed (apply merge (map need-to-filename host-requirements))
        target-packages-needed (apply merge (map need-to-filename target-requirements))
        pkg-names-needed (set (concat (keys host-packages-needed) (keys target-packages-needed)))
        pkgs-needed (concat (map need-to-pkg-availability host-requirements) (map need-to-pkg-availability target-requirements))
        failure-reasons (concat (filter spec-not-parsable host-packages-needed) (filter spec-not-parsable target-packages-needed))
        ]
    {:files-needed pkg-names-needed
     :unparsable-specs unfindable
     :unavailable-packages failure-reasons
     :host-requirements host-requirements
     :target-requirements target-requirements
     :pkgs-needed pkgs-needed}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;> WEBAPP PART HERE <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def BASE_URL (atom "/tmp/dev/"))

(defn strip-trailing-slash [thing]
  (if (and (= \/ (last thing)) (> (count thing) 1))
    (subs thing 0 (-> thing count dec))
    thing))

(defn url [& args]
  (let [append (clojure.string/join (map #(if (keyword? %) (name %) %) args))]
    (if (and (= \/ (first append)) (= \/ (last @BASE_URL)))
      (str @BASE_URL (subs append 1))
      (if (and (not= \/ (first append)) (not= \/ (last @BASE_URL)))
        (str @BASE_URL "/" append)
        (str @BASE_URL append)))))

(defn wrap-page [resp & {:keys [title-annendum]}]
  (->
    (hiccup-page/html5 [:html (into [:head
                                     [:title (str "DXPB"
                                                  (if title-annendum (str " - " title-annendum)))]
                                     [:meta {:name :viewport :content "width=device-width"}]
                                     [:link {:rel :stylesheet :href "https://cdn.jsdelivr.net/gh/kognise/water.css@latest/dist/light.min.css"}]
                                     [:style {:type :text/css} (-> "web/public/main.css" io/resource slurp)]
                                     ])
                        [:body
                         [:div.main resp]]])
    ring.util.response/response
    (ring.util.response/content-type "text/html")))

(defn main-interactive-page [req]
  (wrap-page [:main {}
              [:div
               [:a {:href (url "/v1/pre-build-requirements")} "All Packages"]]
              [:div
               [:a {:href (url "/v1/pre-build-requirements-bootstrap")} "Bootstrap Packages"]]
              ]))

(defn pkgnames-list-page [req list-of-pkgnames title-annendum]
  (let [all-pkgnames list-of-pkgnames
        all-tgtarches (vec (set (map :XBPS_TARGET_ARCH ARCH_PAIRS)))
        pairs-in-buckets (for [tgtarch all-tgtarches]
                           (filter #(= (:XBPS_TARGET_ARCH %) tgtarch) ARCH_PAIRS))
        arch-building-pairs-associative (zipmap all-tgtarches pairs-in-buckets)
        pkg-list-table-head (into [:thead {} [:th "Package Name"]]
                              (for [arch all-tgtarches]
                                [:th {} "Target: " arch]))
        give-pkg-list-table-row (fn [pkgname]
                                  (into [:tr {} [:td pkgname]]
                                        (for [tgt all-tgtarches]
                                          (into [:td {}]
                                                (for [arch-pair (get arch-building-pairs-associative tgt)]
                                                  (let [hostarch (:XBPS_ARCH arch-pair)
                                                        tgtarch (:XBPS_TARGET_ARCH arch-pair)
                                                        iscross (:cross arch-pair)]
                                                    [:a {:href (url "/v1/pre-build-requirements/" pkgname "/" hostarch "/" tgtarch "/" iscross)}
                                                     (str "Host: " hostarch " Target: " tgtarch (if iscross " (cross)"))]))))))]
    (wrap-page [:main {}
                [:div {} [:h3 title-annendum]
                 (into [:table {} pkg-list-table-head]
                   (map give-pkg-list-table-row all-pkgnames))]
                ]
               :title-annendum title-annendum)))

(defn all-packages-page [req]
  (pkgnames-list-page req (sort (list-of-all-pkgnames)) "All Packages"))

(defn bootstrap-packages-page [req]
  (pkgnames-list-page req (sort (list-of-bootstrap-pkgnames)) "Bootstrap Packages"))

(defn build-requirement-page [pkgname host-arch tgt-arch cross]
  (fn [req]
    (let [build-env {:XBPS_ARCH host-arch :XBPS_TARGET_ARCH tgt-arch :cross cross}
          build-env (if (> (.indexOf ARCH_PAIRS build-env) -1) build-env nil)
          version (pkg-version pkgname)
          {:keys [pkgs-needed files-needed unparsable-specs unavailable-packages]} (pkg-requires-to-build :pkgname pkgname :build-env build-env)]
      (if (and version build-env)
        (wrap-page
          [:main {}
           [:table {}
            [:tr [:td {} "Package name"] [:td {} pkgname]]
            [:tr [:td {} "Current Version"] [:td {} version]]
            [:tr [:td {} "Host Arch"] [:td {} host-arch]]
            [:tr [:td {} "Target Arch"] [:td {} tgt-arch]]
            [:tr [:td {} "Cross-build"] [:td {} cross]]]
           (if (not (empty? unparsable-specs))
             (into [:table {} [:thead [:th {} "Confusing requirements"]]]
                   (for [package-file unparsable-specs]
                     [:tr {} [:td {} package-file]]))
             [:p {} "All dependencies could be found as packages"])
           (if (not (empty? unavailable-packages))
             (into [:table {} [:thead [:th {} "Unavailable Dependency"] [:th {} "Reason"]]]
                   (for [[unavailable info] unavailable-packages]
                     [:tr {} [:td {} unavailable] [:td {} (str info)]]))
             [:p {} "Versions were sufficient for all dependencies"])
           (if (not (empty? files-needed))
             (into [:table {} [:thead [:th {} "File Required"]]]
                   (for [package-file files-needed]
                     [:tr {} [:td {} package-file]]))
             [:p {} "No dependencies"])
           (if (not (empty? pkgs-needed))
             (into [:table {} [:thead [:th {} "Package Required Name"] [:th {} "Version"] [:th {} "Arch"] [:th {} "Present"]]]
                   (for [package-detail pkgs-needed]
                     [:tr {}
                      [:td {} (:pkgname package-detail)]
                      [:td {} (:version package-detail)]
                      [:td {} (:arch package-detail)]
                      [:td {} (:in-repo package-detail)]
                      ]))
             [:p {} "No dependencies"])
           ]
          :title-annendum (str pkgname " - needs"))
        (assoc
          (wrap-page
            [:main {}
             [:h3 "Package or build environment could not be found"]] :title-annendum "404")
          :status 404)))
    ))

(comp/defroutes dxpb-routes
  (comp/context (strip-trailing-slash @BASE_URL) []
		(comp/GET "/" [] main-interactive-page)
		(comp/GET "/reset-caches" [] (fn [req] (repo-reader :force true) (main-interactive-page [req])))
		(comp/GET "/v1/pre-build-requirements" [] all-packages-page)
		(comp/GET "/v1/pre-build-requirements-bootstrap" [] bootstrap-packages-page)
		(comp/GET "/v1/pre-build-requirements/:pkgname/:host-arch/:tgt-arch/:cross" [pkgname host-arch tgt-arch cross] (build-requirement-page (str pkgname) (str host-arch) (str tgt-arch) (= cross "true")))
                ))

(defn ignore-trailing-slash
  "Modifies the request uri before calling the handler.
  Removes a single trailing slash from the end of the uri if present.
  Useful for handling optional trailing slashes until Compojure's route matching syntax supports regex.
  Adapted from http://stackoverflow.com/questions/8380468/compojure-regex-for-matching-a-trailing-slash"
  [handler]
  (fn [request]
    (let [uri (:uri request)]
      (handler (assoc request :uri (if (and (not (= "/" uri))
                                            (.endsWith uri "/"))
                                     (subs uri 0 (dec (count uri)))
                                     uri))))))

(defn resp-404 [req]
  (assoc
    (wrap-page [:main {} [:h3 "404 - route not found"]] :title-annendum "404")
    :status 404))

(defn wrap-default-404 [handler]
  (fn [req]
    (if-let [resp (handler req)]
      resp
      (resp-404 req))))

(def rosie ; ring around a
  (-> dxpb-routes
      ignore-trailing-slash
      wrap-default-404
      (rd/wrap-defaults (-> rd/site-defaults (assoc :proxy true)))
      ))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [port (Integer/valueOf (or (System/getenv "DXPB_PORT") "3001"))]
    (println "Running server now on port " port)
    (run-jetty rosie {:port port})))
