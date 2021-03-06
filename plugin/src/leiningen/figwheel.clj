(ns leiningen.figwheel
  (:refer-clojure :exclude [test])
  (:require
   [clojure.pprint :as pp]
   [leiningen.cljsbuild.config :as config]
   [leiningen.cljsbuild.subproject :as subproject]
   [leiningen.core.eval :as leval]
   [clojure.java.io :as io]
   [clojure.string :as string]))

(def figwheel-sidecar-version
  (let [[_ coords version]
        (-> (or (io/resource "META-INF/leiningen/figwheel-sidecar/figwheel-sidecar/project.clj")
                ; this should only ever come into play when testing figwheel-sidecar itself
                "project.clj")
            slurp
            read-string)]
    (assert (= coords 'figwheel-sidecar)
            (str "Something very wrong, could not find figwheel-sidecar's project.clj, actually found: "
                 coords))
    (assert (string? version)
            (str "Something went wrong, version of figwheel-sidecar is not a string: "
                 version))
    version))

;; well this is private in the leiningen.cljsbuild ns
(defn- run-local-project [project crossover-path builds requires form]
  (let [project' (-> project
                     (update-in [:dependencies] conj ['figwheel-sidecar figwheel-sidecar-version]) 
                     (subproject/make-subproject crossover-path builds)
                     #_(update-in [:dependencies] #(filter (fn [[n _]] (not= n 'cljsbuild)) %)))] 
    (leval/eval-in-project project'
     `(try
        (do
          ~form
          (System/exit 0))
        (catch cljsbuild.test.TestsFailedException e#
                                        ; Do not print stack trace on test failure
          (System/exit 1))
        (catch Exception e#
          (do
            (.printStackTrace e#)
           (System/exit 1))))
     requires)))

;; this is really deviating from cljsbuild at this point.
;; need to dig in and probably rewrite this

;; need to get rid of crossovers soon

(defn run-compiler [project {:keys [crossover-path crossovers builds]} figwheel-options]
  ; If crossover-path does not exist before eval-in-project is called,
  ; the files it contains won't be classloadable, for some reason.
  (when (not-empty crossovers)
    (println "\033[31mWARNING: lein-cljsbuild crossovers are deprecated, and will be removed in future versions.\n
See https://github.com/emezeske/lein-cljsbuild/blob/master/doc/CROSSOVERS.md for details.\033[0m")
    (.mkdirs (io/file crossover-path)))
  (let [parsed-builds (map config/parse-notify-command builds)]
    (run-local-project project crossover-path parsed-builds
     '(require 'cljsbuild.crossover 'cljsbuild.util 'clj-stacktrace.repl 'figwheel-sidecar.auto-builder 'figwheel-sidecar.core)
     `(do
        (letfn [(copy-crossovers# []
                   (cljsbuild.crossover/copy-crossovers
                    ~crossover-path
                    '~crossovers))]
          (when (not-empty '~crossovers)
            (copy-crossovers#)
            (cljsbuild.util/once-every-bg 1000 "copying crossovers" copy-crossovers#))
          (if (= (:repl ~figwheel-options) false)
            (do
              (figwheel-sidecar.auto-builder/autobuild*
               { :builds '~parsed-builds
                 :figwheel-server (figwheel-sidecar.core/start-server ~figwheel-options)})
               ;; block because call is non blocking core async
              (loop []
                (Thread/sleep 30000)
                (recur)))
            (figwheel-sidecar.auto-builder/autobuild-repl
             { :builds '~parsed-builds
               :figwheel-server (figwheel-sidecar.core/start-server ~figwheel-options)})))))))

(defn optimizations-none?
  "returns true if a build has :optimizations set to :none"
  [build]
  (= :none (get-in build [:compiler :optimizations])))

;; checking to see if output dir is in right directory
(defn norm-path
  "Normalize paths to a forward slash separator to fix windows paths"
  [p] (string/replace p  "\\" "/"))

(defn relativize-resource-paths
  "Relativize to the local root just in case we have an absolute path"
  [resource-paths]
  (mapv #(string/replace-first (norm-path %)
                               (str (norm-path (.getCanonicalPath (io/file ".")))
                                    "/") "") resource-paths))

(defn make-serve-from-display [{:keys [http-server-root resource-paths] :as opts}]
  (let [paths (relativize-resource-paths resource-paths)]
    (str "(" (string/join "|" paths) ")/" http-server-root)))

(defn output-dir-in-resources-root?
  "Check if the build output directory is in or below any of the configured resources directories."
  [{:keys [output-dir] :as build-options}
   {:keys [resource-paths http-server-root] :as opts}]
  (and output-dir
       (first (filter (fn [x] (.startsWith output-dir (str x "/" http-server-root)))
                      (relativize-resource-paths resource-paths)))))

(defn map-to-vec-builds
  "Cljsbuild allows a builds to be specified as maps. We acommodate that with this function
   to normalize the map back to the standard vector specification. The key is placed into the
   build under the :id key."
  [builds]
  (if (map? builds)
    (vec (map (fn [[k v]] (assoc v :id (name k))) builds))
    builds))

(defn narrow-builds* 
  "Filters builds to the chosen build-ids or if no build-ids specified returns the first
   build with optimizations set to none."
  [builds build-ids]
  (let [builds (map-to-vec-builds builds)
        ;; ensure string ids
        builds (map #(update-in % [:id] name) builds)]
    (vec
     (keep identity
           (if-not (empty? build-ids)
             (keep (fn [bid] (first (filter #(= bid (:id %)) builds))) build-ids)
             [(first (filter optimizations-none? builds))])))))

;; we are only going to work on one build
;; still need to narrow this to optimizations none
(defn narrow-builds
  "Filters builds to the chosen build-id or if no build id specified returns the first
   build with optimizations set to none."
  [project build-ids]
  (update-in project [:cljsbuild :builds] narrow-builds* build-ids))

(defn check-for-valid-options
  "Check for various configuration anomalies."
  [{:keys [http-server-root] :as opts} build']
  (let [build-options (:compiler build')
        opts? (and (not (nil? build-options)) (optimizations-none? build'))
        out-dir? (output-dir-in-resources-root? build-options opts)]
    (map
     #(str "Figwheel Config Error (in project.clj) - " %)
     (filter identity
             (list
              (when-not opts?
                "you have build :optimizations set to something other than :none")
              (when-not out-dir?
                (str
                 (if (:output-dir build-options)
                   "your build :output-dir is not in a resources directory."
                   "you have not configured an :output-dir in your build")
                 (str "\nIt should match this pattern: " (make-serve-from-display opts)))))))))

(defn check-config [figwheel-options builds]
  (if (empty? builds)
    (list
     (str "Figwheel: "
          "No cljsbuild specified. You may have mistyped the build "
          "id on the command line or failed to specify a build in "
          "the :cljsbuild section of your project.clj. You need to have "
          "at least one build with :optimizations set to :none."))
    (mapcat (partial check-for-valid-options figwheel-options) builds)))

(defn normalize-dir
  "If directory ends with '/' then truncate the trailing forward slash."
  [dir]
  (if (and dir (< 1 (count dir)) (re-matches #".*\/$" dir)) 
    (subs dir 0 (dec (count dir)))
    dir))

(defn normalize-output-dir [opts]
  (update-in opts [:output-dir] normalize-dir))

(defn apply-to-key
  "applies a function to a key, if key is defined."
  [f k opts]
  (if (k opts) (update-in opts [k] f) opts))

(defn prep-options
  "Normalize various configuration input."
  [opts]
  (->> opts
       normalize-output-dir
       (apply-to-key str :ring-handler)
       (apply-to-key vec :css-dirs)
       (apply-to-key vec :resource-paths)))

(defn figwheel
  "Autocompile ClojureScript and serve the changes over a websocket (+ plus static file server)."
  [project & build-ids]
  (let [project (narrow-builds project build-ids)
        builds (get-in project [:cljsbuild :builds])
        figwheel-options (prep-options
                          (merge
                           (:figwheel project)
                           ;; this is for the initial deps files checksums
                           ;; better to pass all the builds
                           { :output-dir (get-in (first builds) [:compiler :output-dir]) 
                             :output-to (get-in (first builds) [:compiler :output-to]) }
                           (select-keys project [:resource-paths])))]
    (let [errors (check-config figwheel-options builds)]
      (println (str "Figwheel: focusing on build-ids ("
                    (string/join " " (map :id builds)) ")"))
      (if (empty? errors)
        (run-compiler project
                      (config/extract-options project)
                      figwheel-options)
        (mapv println errors)))))
