(ns dxpb-clj.core
  (:gen-class)
  (:import java.io.File)
  (:require [clojure.core.async :as async :refer [>!! <!! chan thread pipeline-async close!]]
            [clojure.java.shell :as shell :refer [sh]]
            [clojure.string :refer [split trim capitalize] :as string]
            [clojure.set :refer [union difference intersection rename-keys]]
            [compojure.core :as comp]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :as rd]
            [ring.util.response :as ring-response]
            [hiccup.page :as hiccup-page]
            [clojure.java.io :as io]
            [config.core :refer [env]]
            [dxpb-clj.db :as db :refer [take-instruction
                                        does-pkgname-exist
                                        get-pkg-data
                                        get-all-needs-for-arch
                                        pkg-is-noarch
                                        pkg-version
                                        list-of-all-pkgnames
                                        list-of-bootstrap-pkgnames
                                        insert-arch-spec
                                        remove-arch-spec
                                        arch-spec-present?
                                        arch-specs]]
            [dxpb-clj.repo-reader :refer [get-repo-reader]]
            [dxpb-clj.execute-build :refer [get-executor]]
            [org.tobereplaced.nio.file :refer [symbolic-link? file-name]]))

(def REPO_READER (atom nil))

(def BUILD_EXECUTOR (atom nil))

(defn repo-reader [& {:keys [force mechanism] :or {force false
                                                   mechanism :file+repodata}}]
  (if (or (nil? @REPO_READER) force)
    (reset! REPO_READER (get-repo-reader mechanism))
    @REPO_READER))

(defn build-executor [& {:keys [force mechanism] :or {force false
                                                      mechanism :basic-shell}}]
  (if (or (nil? @BUILD_EXECUTOR) force)
    (reset! BUILD_EXECUTOR (get-executor mechanism))
    @BUILD_EXECUTOR))

#_ (build-executor :force true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;> ARCH SPECIFICATIONS HERE <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ALL_ARCH_SPECS
  (agent (set nil)
         :error-mode :fail
         :validator (fn [new-state]
                      (and (set? new-state)
                           (reduce (fn [ok? {:keys [XBPS_ARCH XBPS_TARGET_ARCH cross]}]
                                     (and ok?
                                          (some? XBPS_ARCH)
                                          (some? XBPS_TARGET_ARCH)
                                          (some? cross)))
                                   true
                                   new-state)))))

#_ (prn @ALL_ARCH_SPECS)
;; succeeds:
#_ (send ALL_ARCH_SPECS conj {:XBPS_ARCH "x86_64" :XBPS_TARGET_ARCH "x86_64" :cross false})
#_ (send ALL_ARCH_SPECS conj {:XBPS_ARCH "x86_64-musl" :XBPS_TARGET_ARCH "x86_64-musl" :cross false})
;; fails and ends mutation of ALL_ARCH_SPECS due to missing a key
#_ (send ALL_ARCH_SPECS conj {:XBPS_ARCH "x86_64" :XBPS_TARGET_ARCH "x86_64"})

(defn start-arch-specs []
  (doall (map (partial send ALL_ARCH_SPECS disj) @ALL_ARCH_SPECS))
  (let [next-specs (set
                     (union (into [] (comp (map name)
                                           (filter (fn [in] (string/starts-with? in "arch-spec-")))
                                           (map keyword)
                                           (map (partial get env)))
                                  (keys env))
                            (arch-specs)))]
    (doall (map (partial send ALL_ARCH_SPECS conj) next-specs))
    (doall (map (partial insert-arch-spec) next-specs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;> END OF ARCH SPECIFICATIONS <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;> READ IN PACKAGES HERE <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sh-wrap [& {:keys [environment dir cmd]}]
  (shell/with-sh-env environment
    (shell/with-sh-dir
      dir
      (apply sh cmd))))

(defn xbps-src-dbulk-dump
  "Take in an archspec and a pkgname, return the xbps-src result for it.
  Return val is a map {:out valid-only-unless :exit value ::pkgname that-was-commanded}"
  [{:keys [XBPS_ARCH XBPS_TARGET_ARCH cross] :as arch-spec} pkgname]
  (let [command (if cross
                  ["./xbps-src" "-a" XBPS_TARGET_ARCH "dbulk-dump" pkgname]
                  ["./xbps-src" "dbulk-dump" pkgname])]
    (-> (sh-wrap :environment arch-spec :dir (:owned-packages-path env) :cmd command)
      (assoc ::pkgname pkgname)
      (assoc ::arch-spec arch-spec))))

(defn wrap-for-pipe [fn1]
  (fn [pkgname result]
    (thread
      (>!! result (fn1 pkgname))
      (close! result))))

#_ (= (parse-dbulk-dump "pkgname: spim\nversion: 8.0\nrevision: 2\nhostmakedepends:\n flex\n gcc")
      {:pkgname "spim" :version "8.0" :revision "2" :hostmakedepends '("flex" "gcc")})

#_ (= (parse-dbulk-dump "subpackages:\n pkga\n  depends:\n   depa\n   depb\n pkgb\n pkgc\n pkgd\n  depends:\n   depc")
      {:subpackages '("pkga" " depends:" "  depa" "  depb" "pkgb" "pkgc" "pkgd" " depends:" "  depc")})

(defn parse-dbulk-dump
  "Take in the string output of ./xbps-src dbulk-dump
  If the text is well formed, return a map with the raw parse
    This map may have a :subpackages key to parse out later.
  If the text is not well formed, return nil."
  [dump]
  (let [starts-with-space #(string/starts-with? % " ")
        drop-first-space #(subs % 1)]
    (loop [lines (cond
                   (string? dump) (split dump #"\n")
                   (seq dump) dump)
           rV {}]
      (if (empty? lines)
        rV
        (let [line (split (first lines) #" ")
              this-line-as-keyword (-> line
                                       first
                                       drop-last
                                       string/join
                                       keyword)]
          (cond ;; nil if malformed, recur if reasonable
            (and (= (count line) 1) ;; no value, now we need to get the rest of the list
                 (string/ends-with? (first line) ":")) (let [[these-items next-items] (split-with starts-with-space (rest lines))]
                                                         (recur next-items
                                                                (assoc rV this-line-as-keyword (map drop-first-space these-items))))
            (and (> (count line) 1) ;; a simple value set
                 (string/ends-with? (first line) ":")
                 (seq (first line))) (recur (rest lines)
                                            (assoc rV this-line-as-keyword (if (= (count (rest line)) 1)
                                                                             (last line)
                                                                             (rest line))))))))))

(defn merge-raw-dbulk-dump-version-string [in]
  (-> in
      (assoc :version (str (:version in) "_" (:revision in)))
      (dissoc :revision)))

#_ (= (-> (parse-dbulk-dump "subpackages:\n pkga\n  depends:\n   depa\n   depb\n pkgb\n pkgc\n pkgd\n  depends:\n   depc")
          :subpackages
          partition-subpackages)
      '(("pkga" " depends:" "  depa" "  depb") ("pkgb") ("pkgc") ("pkgd" " depends:" "  depc")))

(defn partition-subpackages [subpackages-from-dbulk-dump]
  (loop [left-to-parse subpackages-from-dbulk-dump
         rV []
         pkg-num -1]
    (if (empty? left-to-parse)
      rV
      (if (string/starts-with? (first left-to-parse) " ")
        (recur (rest left-to-parse)
               (update rV pkg-num conj (first left-to-parse))
               pkg-num)
        (recur (rest left-to-parse)
               (conj rV (vector (first left-to-parse)))
               (inc pkg-num))))))

(defn subpackage-from-dbulk-dump->package [main-pkgname version in]
  (merge (parse-dbulk-dump (map #(subs % 1) (rest in)))
         {:pkgname (first in)
          :version version
          :dxpb/main-package main-pkgname}))

#_ (= (defn test1 [] (->> (parse-dbulk-dump "pkgname: spim\nversion: 8.0\nrevision: 2\nhostmakedepends:\n flex\n gcc\nsubpackages:\n pkga\n  depends:\n   depa\n   depb\n pkgb\n pkgc\n pkgd\n  depends:\n   depc")
          merge-raw-dbulk-dump-version-string
          derive-subpackages-from-dbulk-dump
          (map package-val-lists->sets)))
      '({:pkgname "spim", :version "8.0_2", :hostmakedepends ("flex" "gcc"), :dxpb/sub-packages #{"pkgb" "pkga" "pkgd" "pkgc"}}
        {:depends ("depa" "depb"), :pkgname "pkga", :version "8.0_2", :dxpb/main-package "spim"}
        {:pkgname "pkgb", :version "8.0_2", :dxpb/main-package "spim"}
        {:pkgname "pkgc", :version "8.0_2", :dxpb/main-package "spim"}
        {:depends ("depc"), :pkgname "pkgd", :version "8.0_2", :dxpb/main-package "spim"}))

(defn derive-subpackages-from-dbulk-dump [{:keys [pkgname subpackages version] :as package}]
  (let [packages-of-subpackages (map (partial subpackage-from-dbulk-dump->package pkgname version)
                                     (partition-subpackages subpackages))]
    (conj packages-of-subpackages
          (-> package
              (dissoc :subpackages)
              (assoc :dxpb/sub-packages (set (map :pkgname packages-of-subpackages)))))))

(defn package-val-lists->sets [package-map]
  (into {} (map #(if (string? (val %)) ;; we only have 1 kind of steady val: string
                   [(key %) (val %)]
                   [(key %) (set (val %))]) ;; hard to test list? because lazy seq
                package-map)))

(defn dbulk-dump-str->list-of-packages [in]
  (->> in
       parse-dbulk-dump
       merge-raw-dbulk-dump-version-string
       derive-subpackages-from-dbulk-dump
       (map package-val-lists->sets)))

(defmulti dbulk-dump-cmd->info
  (fn [{:keys [exit]}]
    (if (zero? exit)
      ::data-ok
      ::delete-pkg)))

(defmethod dbulk-dump-cmd->info ::data-ok [{:keys [out]
                                            {:keys [XBPS_ARCH XBPS_TARGET_ARCH cross] :as arch-spec} ::arch-spec}]
  (let [pkgname->cruxid #(str ":package:" (:pkgname %)
                              ":target:" (:dxpb/targetarch %)
                              ":host:" (:dxpb/hostarch %)
                              ":cross:" (:dxpb/crossbuild %))]
    (->> out
         dbulk-dump-str->list-of-packages
         (map #(assoc % :dxpb/hostarch XBPS_ARCH))
         (map #(assoc % :dxpb/targetarch XBPS_TARGET_ARCH))
         (map #(assoc % :dxpb/crossbuild (boolean cross)))
         (map #(assoc % :crux.db/id (pkgname->cruxid %)))
         (map #(assoc % :dxpb/type :package)))))

(defmethod dbulk-dump-cmd->info ::delete-pkg [{::keys [pkgname arch-spec]}]
  {:pkgname pkgname
   :arch-spec arch-spec
   :delete true})

(defmulti dbulk-dump-info->database-command
  (fn [data]
    (if (:delete data)
      ::delete-pkg
      ::data-ok)))

(defmethod dbulk-dump-info->database-command ::data-ok [info]
  (conj (mapv (partial vector :crux.tx/put) info)
        [:crux.tx/fn :ensure-pruned-subpkgs]))

(defmethod dbulk-dump-info->database-command ::delete-pkg [{:keys [pkgname arch-spec]}]
  [[:crux.tx/fn :delete-package pkgname arch-spec]])

(defn chan-write-all [c in]
  (when in
    (async/put! c (first in) (fn [_] (chan-write-all c (next in))))))

#_ (list-of-all-package-names-to-read)

(defn list-of-all-package-names-to-read []
  {:pre [(:owned-packages-path env)]
   :post [(seq %)]}
  (->> (str (:owned-packages-path env) "/srcpkgs")
       File.
       .listFiles
       (filter (complement symbolic-link?))
       (map (comp str file-name))))

(defn import-arch-spec
  [& {:keys [file-list arch-spec]}]
  {:pre [(seq file-list) (map? arch-spec)]}
  (prn file-list)
  (let [total-num-files (count file-list)
        pkgnames (chan 1000)
        pkginfo (chan 1000 (comp (map dbulk-dump-cmd->info)
                                 (map dbulk-dump-info->database-command)))]
    (pipeline-async (get env :num-xbps-src-readers 15)
                    pkginfo
                    (wrap-for-pipe (partial xbps-src-dbulk-dump arch-spec))
                    pkgnames)
    (chan-write-all pkgnames file-list)
    (loop [done 0]
      (println done "/" total-num-files)
      (take-instruction (<!! pkginfo))
      (when (not= (inc done) total-num-files)
        (recur (inc done))))))

#_ (import-packages)
#_ (import-packages :package-list ["gcc"])
#_ (db/arch-spec->db-key {:XBPS_ARCH "x86_64" :XBPS_TARGET_ARCH "x86_64" :cross false})
#_ (insert-arch-spec {:XBPS_ARCH "x86_64" :XBPS_TARGET_ARCH "x86_64" :cross false})
#_ (insert-arch-spec {:XBPS_ARCH "x86_64-musl" :XBPS_TARGET_ARCH "x86_64-musl" :cross false})
#_ (remove-arch-spec {:XBPS_ARCH "x86_64" :XBPS_TARGET_ARCH "x86_64" :cross false})
#_ (start-arch-specs)
#_ (prn @ALL_ARCH_SPECS)

(defn import-packages
  "Pass in :arch-spec to only bootstrap a specific arch spec.
  Pass in a file list to not import every package in the tree"
  [& {:keys [arch-spec package-list]}]
  (doall
    (pmap
      (partial import-arch-spec
               :file-list (or package-list (list-of-all-package-names-to-read))
               :arch-spec)
      (or @ALL_ARCH_SPECS (list arch-spec)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;> END OF READING IN PACKAGES <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;> BEGIN THE PART WITH THE GRAPH <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
          (when (zero? exit)
            {:as-key (keyword (trim out)) :version-specified true :spec pkgname}))
        (let [{:keys [out exit]} (sh "xbps-uhelper" "getpkgname" pkgname)]
          (when (zero? exit)
            {:as-key (keyword (trim out)) :version-specified true :spec pkgname}))))))

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
    (coll? (:spec b)) {:spec (conj (:spec b) (:spec a)) :version-not-specified false :arch (:arch a)}))

(defn obtain-all-deps-of-pkgs-for-arch [& {:keys [pkgnames arch]}]
  (loop [next-specs (set pkgnames)
         seen-specs #{}]
    (if (seq next-specs)
      (let [unseen-specs (difference next-specs seen-specs)]
        (recur (get-all-needs-for-arch :pkgnames unseen-specs :arch arch) (union unseen-specs seen-specs)))
      (obtain-pkgnames seen-specs arch))))

(defn pkgname-to-needs [& {:keys [pkgname build-env]}]
  (when-let [pkgname-as-specified (pkgname-to-key pkgname)]
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
       :unfindable unfound-deps})))

;; Pure function. Can be memoized.
(defn verify-pkg-version-ok [& {:keys [pkgname version specs spec]}]
  (if (nil? spec)
    (if (coll? specs)
      (empty? (filter false? (map #(verify-pkg-version-ok :pkgname pkgname :version version :spec %) specs)))
      (verify-pkg-version-ok :pkgname pkgname :version version :spec specs))
    (let [;; when running the below command, (= exit 1) is a match.
          our-pkg-spec (str pkgname "-" version)
          {:keys [exit]} (sh "xbps-uhelper" "pkgmatch" our-pkg-spec spec)]
      (= exit 1))))

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
                   (:spec need))]
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

(defn pkg-in-repo [pkgname arch]
  ((:package-in-repo? (repo-reader))
   :pkgname pkgname
   :version (pkg-version pkgname)
   :arch arch))

#_ (time (pkg-in-repo "xz" "x86_64-musl"))
#_ (time (pkg-in-repo "chroot-git" "x86_64-musl"))
#_ (time (pkg-version "xz"))
#_ (time (pkg-version "chroot-git"))

(defn pkg-requires-to-build [& {:keys [pkgname build-env]}]
  (let [spec-not-parsable (fn [known-data] (not (true? (val known-data))))
        {:keys [target-requirements host-requirements unfindable]} (pkgname-to-needs :pkgname pkgname :build-env build-env)
        host-packages-needed (apply merge (map need-to-filename host-requirements))
        target-packages-needed (apply merge (map need-to-filename target-requirements))
        pkg-names-needed (set (concat (keys host-packages-needed) (keys target-packages-needed)))
        pkgs-needed (concat (map need-to-pkg-availability host-requirements) (map need-to-pkg-availability target-requirements))
        failure-reasons (concat (filter spec-not-parsable host-packages-needed) (filter spec-not-parsable target-packages-needed))]
    {:files-needed pkg-names-needed
     :unparsable-specs unfindable
     :unavailable-packages failure-reasons
     :host-requirements host-requirements
     :target-requirements target-requirements
     :pkgs-needed pkgs-needed}))

#_ (get-pkg-data (name "gcc") (first @ALL_ARCH_SPECS))
#_ (pkgname-to-needs :pkgname "gcc" :build-env (first @ALL_ARCH_SPECS))
#_ (pkg-requires-to-build :pkgname "gcc" :build-env (first @ALL_ARCH_SPECS))

(defn arch-pairs-for-target [target-arch]
  (filter (comp (partial = target-arch) :XBPS_TARGET_ARCH) @ALL_ARCH_SPECS))

(defn pkg-deps-satisfied-for-build [& {:keys [pkgname target-arch build-env as-boolean] :or {as-boolean false}}]
  (if build-env
    (let [{:keys [target-requirements
                  host-requirements
                  unfindable]} (pkgname-to-needs
                                 :pkgname pkgname
                                 :build-env build-env)
          pkgs-in-repo (map :in-repo
                            (concat (map need-to-pkg-availability host-requirements)
                                    (map need-to-pkg-availability target-requirements)))]
      (and (empty? unfindable) (empty? (filter false? pkgs-in-repo))))
    (let [valid-envs (arch-pairs-for-target target-arch)
          rV (zipmap valid-envs
                     (map (partial pkg-deps-satisfied-for-build
                                   :pkgname
                                   pkgname
                                   :build-env)
                          valid-envs))]
      (if as-boolean
        (seq (filter true? (vals rV)))
        rV))))

(defn get-which-packages-to-build [& {:keys [list-of-pkgnames build-env take-only]}]
  {:pre [(some? build-env) (some? list-of-pkgnames)]}
  ;;; Need to find a list of package names
  ;;; Then return the set of packagenames that can be built
  ;;; Maybe do this in 2 phases, bootstrap first, then the rest?
  ;;; For each pkgname, if it isn't present, and can be built, then return its name.
  (let [take-some-or-all (if (number? take-only)
                           (partial take take-only)
                           identity)
        bootstrap-list (list-of-bootstrap-pkgnames)
        pkg-can-and-should-be-built (fn [arch pkgname-in]
                                             (when (pkg-in-repo pkgname-in arch)
                                               (pkg-deps-satisfied-for-build :pkgname pkgname-in :build-env build-env)))
        absent-bootstrap-packages (filter (complement #(pkg-in-repo % (:XBPS_TARGET_ARCH build-env))) bootstrap-list)
        bootstrap-packages-to-build (->> absent-bootstrap-packages
                                         (filter (partial pkg-deps-satisfied-for-build :build-env build-env :pkgname))
                                         take-some-or-all)]
    (if (seq absent-bootstrap-packages)
      {(:XBPS_TARGET_ARCH build-env) bootstrap-packages-to-build}
      (let [all-needs (map (partial pkgname-to-needs :build-env build-env :pkgname) list-of-pkgnames)
            all-target-requirements (merge-with merge-obtained-pkgnames (map :target-requirements all-needs))]
        {(:XBPS_TARGET_ARCH build-env) (take-some-or-all (filter (partial pkg-can-and-should-be-built (:XBPS_TARGET_ARCH build-env)) all-target-requirements))
         :_ (apply concat (map :unfindable all-needs))}))))

#_ (filter (complement #(pkg-in-repo % (:XBPS_TARGET_ARCH (first @ALL_ARCH_SPECS)))) (list-of-bootstrap-pkgnames))
#_ (let [build-env (first @ALL_ARCH_SPECS)
         bootstrap-pkgs (list-of-bootstrap-pkgnames)
         absent-bootstrap-packages (filter (complement #(pkg-in-repo % (:XBPS_TARGET_ARCH build-env))) bootstrap-pkgs)]
     (filter (partial pkg-deps-satisfied-for-build :build-env build-env :pkgname) absent-bootstrap-packages))
#_ (get-which-packages-to-build :list-of-pkgnames [] :build-env (first @ALL_ARCH_SPECS))

(defn all-pkgs-to-build [list-of-pkgnames & {:keys [take-only]}]
  (loop [envs-to-process @ALL_ARCH_SPECS
         rV {}]
    (if (empty? envs-to-process)
      rV
      (let [proc-this (first envs-to-process)]
        (recur (rest envs-to-process)
               (assoc rV proc-this (get-which-packages-to-build :take-only take-only :list-of-pkgnames list-of-pkgnames :build-env proc-this)))))))

#_ (all-pkgs-to-build [] :take-only 1)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;> END OF PART WITH THE GRAPH <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;> BEGIN THE PART WITH BUILDING <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ACTIVE_BUILDS (atom {}))

(defn trigger-build [& {:keys [pkgname build-env]
                        {:keys [XBPS_ARCH XBPS_TARGET_ARCH cross]} :build-env}]
  {:pre [(some? pkgname) (some? build-env)]}
  (let [{:keys [submit]} (build-executor)]
    (submit :pkgname pkgname :host-arch XBPS_ARCH :target-arch XBPS_TARGET_ARCH :is-cross cross :git-hash nil)))

(defn trigger-and-track-build [& {:keys [pkgname build-env]}]
  (let [key (db/get-pkg-key pkgname build-env)]
    (if (nil? (get @ACTIVE_BUILDS key))
      (let [build (trigger-build :pkgname pkgname :build-env build-env)]
        (swap! ACTIVE_BUILDS assoc key build)))))

#_ (trigger-and-track-build :pkgname "xz" :build-env (first @ALL_ARCH_SPECS))

#_ (def __a (all-pkgs-to-build [] :take-only 1))
#_ (prn (key (vals (second (prn __a)))))
#_ (for [sets (all-pkgs-to-build [] :take-only 1)]
     (if (seq (second sets))
       (doall (map (partial trigger-and-track-build :build-env (key sets) :pkgname) (second sets)))))

;; Schedule some builds!!!!!
#_ (for [[env packages] (all-pkgs-to-build [] :take-only 10)]
     (let [to-build (get packages (:XBPS_TARGET_ARCH env))]
       (if (seq to-build)
         (doall (map (partial trigger-and-track-build :build-env env :pkgname) to-build)))))

#_ (prn @ACTIVE_BUILDS)

#_ (prn (= {:exit 0 :out "" :err ""} ((:result? (val (first @ACTIVE_BUILDS))))))

#_ (filter false?
           (for [build @ACTIVE_BUILDS]
             (if (= {:exit 0 :out "" :err ""} ((:result? (val build))))
               true
               false)))

(defn build-until-done []
  (loop [ongoing-builds []
         to-build []
         no-stop true
         failed-packages []]
    (prn "Ongoing:" ongoing-builds)
    (prn "To build:" to-build)
    (cond
      (and (empty? ongoing-builds)
           (empty? to-build)
           (not no-stop)) {:failed failed-packages}
      (and (empty? ongoing-builds)
           (empty? to-build)
           no-stop) (do
                      (prn "Scheduling more builds")
                      (recur ongoing-builds (all-pkgs-to-build [] :take-only 10) false failed-packages))
      (seq to-build) (let [[env packages] (first to-build)
                           ok-packages (get packages (:XBPS_TARGET_ARCH env))
                           next-failed-packages (get packages :_)
                           new-builds (map (partial trigger-build :build-env env :pkgname) ok-packages)
                           new-ongoing (concat ongoing-builds new-builds)
                           new-failed (concat failed-packages next-failed-packages)]
                       (recur new-ongoing (rest to-build) false new-failed))
      (seq ongoing-builds) (case ((:result? (first ongoing-builds)))
                             :done (recur (rest ongoing-builds) to-build true failed-packages)
                             :failed (recur (rest ongoing-builds) to-build true (conj failed-packages (first ongoing-builds)))
                             (do
                               (prn ((:result? (first ongoing-builds))))
                               (Thread/sleep 10000)
                               (recur ongoing-builds to-build true failed-packages))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;>  END THE PART WITH BUILDING  <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;> WEBAPP PART HERE <;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def BASE_URL (atom "/tmp/dev/"))

(defn strip-trailing-slash [thing]
  (if (and (= \/ (last thing)) (> (count thing) 1))
    (subs thing 0 (-> thing count dec))
    thing))

(defn url [& args]
  (let [append (string/join (map #(if (keyword? %) (name %) %) args))]
    (if (and (= \/ (first append)) (= \/ (last @BASE_URL)))
      (str @BASE_URL (subs append 1))
      (if (and (not= \/ (first append)) (not= \/ (last @BASE_URL)))
        (str @BASE_URL "/" append)
        (str @BASE_URL append)))))

(defn hiccup->response [{:keys [hiccup-page-list title-annendum]}]
  (-> [:html (into [:head
                    [:title (str "DXPB"
                                 (when title-annendum (str " - " title-annendum)))]
                    [:meta {:name :viewport :content "width=device-width"}]
                    [:link {:rel :stylesheet :href "https://cdn.jsdelivr.net/gh/kognise/water.css@latest/dist/light.min.css"}]
                    [:style {:type "text/css"} (-> "web/public/main.css" io/resource slurp)]])
       [:body
        [:main {} hiccup-page-list]]]
      hiccup-page/html5
      ring.util.response/response
      (ring.util.response/content-type "text/html")))

(defn main-interactive-page [req]
  (-> req
      (assoc :hiccup-page-list
             (list [:div
                    [:a {:href (url "/v1/pre-build-requirements")} "All Packages"]]
                   [:div
                    [:a {:href (url "/v1/pre-build-requirements-bootstrap")} "Bootstrap Packages"]]))
      hiccup->response))

(defn pkg-list->table [{:keys [pkg-list title-annendum] :as req}]
  (let [all-pkgnames pkg-list
        all-tgtarches (vec (set (map :XBPS_TARGET_ARCH @ALL_ARCH_SPECS)))
        pairs-in-buckets (for [tgtarch all-tgtarches]
                           (filter #(= (:XBPS_TARGET_ARCH %) tgtarch) @ALL_ARCH_SPECS))
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
                                                    [:a {:href (url "/v1/pre-build-requirements/" pkgname "/" tgtarch "/" hostarch "/" iscross)}
                                                     (str "Host: " hostarch " Target: " tgtarch (when iscross " (cross)"))]))))))
        table-to-return [:div {} [:h3 title-annendum]
                         (into [:table {} pkg-list-table-head]
                               (map give-pkg-list-table-row all-pkgnames))]]
    (assoc req :table table-to-return)))

(defn packages-page [kind]
  (let [package-names (case kind
                        :all (list-of-all-pkgnames)
                        :bootstrap (list-of-bootstrap-pkgnames))]
    (fn [req]
      (-> req
          (assoc :pkg-list (sort package-names))
          (assoc :title-annendum (str (capitalize (name kind)) " Packages"))
          pkg-list->table
          (update :table list)
          (rename-keys {:table :hiccup-page-list})
          hiccup->response))))

(defn pass-if-build-env-real [{::keys [build-env] :as in}]
  (when (contains? @ALL_ARCH_SPECS build-env)
    in))

(defn pass-if-version-real [in]
  (when (::version in)
    in))

(defn inject-summary-table [{::keys [pkgname version]
                             {:keys [XBPS_ARCH XBPS_TARGET_ARCH cross]} ::build-env
                             :as in}]
  (assoc in
         ::summary-table
         [:table {}
          [:tr [:td {} "Package name"] [:td {} pkgname]]
          [:tr [:td {} "Current Version"] [:td {} version]]
          [:tr [:td {} "Host Arch"] [:td {} XBPS_ARCH]]
          [:tr [:td {} "Target Arch"] [:td {} XBPS_TARGET_ARCH]]
          [:tr [:td {} "Cross-build"] [:td {} cross]]]))

(defn inject-dumb-package-list-table [{:keys [table-headers table-body-from table-body-empty conj-tables-to fn-table-row-to-element] :as in}]
  (update in
          conj-tables-to
          conj
          (if (seq table-body-from)
            (into [:table {} [:thead {} (map #(vector :th {} %) table-headers)]]
                  (for [table-body-row table-body-from]
                    [(if fn-table-row-to-element
                       (fn-table-row-to-element table-body-row)
                       :tr) {} (map #(vector :td {} %) table-body-row)]))
            table-body-empty)))

(defn assemble-build-requirement-page [{::keys [summary-table] :as in}]
  (assoc in :hiccup-page-list (list summary-table)))

(defn copy-val [in from to]
  ((if (vector? to)
     assoc-in
     assoc)
   in
   to
   (if (vector? from)
     (get-in in from)
     (get in from))))

(defn build-requirement-page [pkgname host-arch tgt-arch cross]
  (fn [req]
    (let [build-env {:XBPS_ARCH host-arch :XBPS_TARGET_ARCH tgt-arch :cross cross}]
      (if-let [resp (some-> req
                            (assoc :title-annendum (str pkgname " - needs"))
                            (assoc ::build-env build-env)
                            pass-if-build-env-real
                            (assoc ::version (pkg-version pkgname))
                            pass-if-version-real
                            (assoc ::pkgname pkgname)
                            (assoc ::dep-details (pkg-requires-to-build :pkgname pkgname :build-env build-env))
                            inject-summary-table
                            (rename-keys {::summary-table :hiccup-page-list})
                            (update :hiccup-page-list vector) ;; temporary so conj appends
                            (assoc :conj-tables-to :hiccup-page-list)
                            ;; Dependencies where DXPB failed to parse
                            (assoc :table-headers ["Confusing Requirements"])
                            (assoc :table-body-empty [:p {} "All dependencies could be found as packages"])
                            (copy-val [::dep-details :unparsable-specs] :table-body-from)
                            inject-dumb-package-list-table
                            ;; Dependencies that can never be met
                            (assoc :table-headers ["Unavailable Dependency" "Reason"])
                            (assoc :table-body-empty [:p {} "Versions were sufficient for all dependencies"])
                            (copy-val [::dep-details :unavailable-packages] :table-body-from)
                            inject-dumb-package-list-table
                            ;; Just the files needed
                            (assoc :table-headers ["File Required"])
                            (assoc :table-body-empty [:p {} "No Dependencies"])
                            (update-in [::dep-details :files-needed] (fn [what] (map #(vector %) what)))
                            (copy-val [::dep-details :files-needed] :table-body-from)
                            inject-dumb-package-list-table
                            ;; To be precise, and whether the packages are available
                            (assoc :table-headers ["Package Required" "Version" "Arch" "Present"])
                            (assoc :table-body-empty nil)
                            (assoc :fn-table-row-to-element (fn [[pkgname _ arch in-repo]]
                                                              (if in-repo
                                                                :tr.present
                                                                (if (pkg-deps-satisfied-for-build :pkgname pkgname
                                                                                                  :target-arch arch
                                                                                                  :as-boolean true)
                                                                  :tr.buildable
                                                                  :tr.unbuildable))))
                            (update-in [::dep-details :pkgs-needed] (fn [what] (map (juxt :pkgname
                                                                                          :version
                                                                                          :arch
                                                                                          :in-repo)
                                                                                    what)))
                            (copy-val [::dep-details :pkgs-needed] :table-body-from)
                            inject-dumb-package-list-table
                            ;; Now on to making this a page.
                            ;; First, ->list so not a hiccup element
                            (update :hiccup-page-list list*)
                            hiccup->response)]
        resp
        (-> req
            (assoc :hiccup-page-list (list [:h3 "Package or build environment could not be found"]))
            (assoc :title-annendum "404")
            hiccup->response
            (assoc :status 404))))))

(comp/defroutes dxpb-routes
  (comp/context (strip-trailing-slash @BASE_URL) []
		(comp/GET "/" [] main-interactive-page)
		(comp/GET "/reset-caches" [] (fn [req] (repo-reader :force true) (main-interactive-page [req])))
		(comp/GET "/v1/pre-build-requirements" [] (packages-page :all))
		(comp/GET "/v1/pre-build-requirements-bootstrap" [] (packages-page :bootstrap))
		(comp/GET "/v1/pre-build-requirements/:pkgname/:tgt-arch/:host-arch/:cross" [pkgname host-arch tgt-arch cross] (build-requirement-page (str pkgname) (str host-arch) (str tgt-arch) (= cross "true")))))

(defn ignore-trailing-slash
  "Modifies the request uri before calling the handler.
  Removes a single trailing slash from the end of the uri if present.
  Useful for handling optional trailing slashes until Compojure's route matching syntax supports regex.
  Adapted from http://stackoverflow.com/questions/8380468/compojure-regex-for-matching-a-trailing-slash"
  [handler]
  (fn [request]
    (let [uri (:uri request)]
      (handler (assoc request :uri (if (and (not= "/" uri)
                                            (.endsWith uri "/"))
                                     (subs uri 0 (dec (count uri)))
                                     uri))))))

(defn resp-404 [req]
  (-> req
      (assoc :hiccup-page-list (list [:h3 "404 - route not found"]
                                     [:a {:href (url)} [:h3 {} "Home"]]))
      (assoc :title-annendum "404")
      hiccup->response
      (assoc :status 404)))

(defn wrap-default-404 [handler]
  (fn [req]
    (if-let [resp (handler req)]
      resp
      (resp-404 req))))

(def rosie ; ring around a
  (-> dxpb-routes
      ignore-trailing-slash
      wrap-default-404
      (rd/wrap-defaults (assoc rd/site-defaults :proxy true))))

(defn -main
  "I don't do a whole lot ... yet."
  [& _args]
  (let [port (Integer/valueOf (or (System/getenv "DXPB_PORT") "3001"))]
    (start-arch-specs)
    (println "Running server now on port " port)
    (run-jetty rosie {:port port})
    (shutdown-agents)))
