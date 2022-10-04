; Copyright 2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns build
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [net.lewisship.build :refer [requiring-invoke]]
            [clojure.tools.build.api :as b]
            [io.pedestal.versions :as v]
            [deps-deploy.deps-deploy :as d]))

(def version-file "VERSION.txt")
(def group-name 'io.pedestal)
(def version (-> version-file slurp str/trim))

(def module-dirs
  ;; Keep these in dependency order
  ["log"
   "interceptor"
   "route"
   "service"
   ;; And then the others:
   "aws"
   "immutant"
   "jetty"
   "service-tools"
   "tomcat"])

;; Working around this problem (bug)?
;; Manifest type not detected when finding deps for io.pedestal/pedestal.log in coordinate #:local{:root "../log"}
;; Basically, not recognizing relative paths correctly; I think they are being evaluated at the top level, so ".." is the
;; directory about the pedestal workspace.
;; See https://clojure.atlassian.net/browse/TDEPS-106

(defn- classpath-for
  [dir overrides]
  (binding [b/*project-root* dir]
    (println "Reading" dir "...")
    (let [basis (b/create-basis {:override-deps overrides}) roots (:classpath-roots basis)]
      (map (fn [path]
             (if (str/starts-with? path "/")
               path
               (str dir "/" path)))
           roots))))

(defn- canonical
  "Expands a relative path to a full path."
  [path]
  (-> path io/file .getAbsolutePath))

(defn- as-override
  [coll dir-name]
  (let [project-name (symbol (name group-name)
                             (str "pedestal." dir-name))
        project-dir (canonical dir-name)]
    (assoc coll project-name {:local/root project-dir})))

(defn codox
  "Generates combined Codox documentation for all sub-projects."
  [_]
  (let [overrides (reduce as-override {} module-dirs)
        project-classpath (mapcat #(classpath-for % overrides) module-dirs)
        codox-classpath (:classpath-roots (b/create-basis {:aliases [:codox]}))
        full-classpath (->> project-classpath
                            (concat codox-classpath)
                            distinct
                            sort)
        codox-config {:metadata {:doc/format :markdown}
                      :name (str (name group-name) " libraries")
                      :version version
                      :source-paths (mapv #(str % "/src") module-dirs)
                      :source-uri "https://github.com/pedestal/pedestal/blob/{version}/{filepath}#L{line}"}
        expression `(do
                      ((requiring-resolve 'codox.main/generate-docs) ~codox-config)
                      ;; Above returns the output directory name, "target/doc", which gets printed
                      ;; by clojure.main, so override that to nil on success here.
                      nil)
        ;; The API version mistakenly requires :basis, so bypass it.
        process-params (requiring-invoke clojure.tools.build.tasks.process/java-command
                                         {:cp full-classpath
                                          :main "clojure.main"
                                          :main-args ["--eval" (pr-str expression)]})
        _ (println "Starting codox ...")
        {:keys [exit]} (b/process process-params)]
    (when-not (zero? exit)
      (println "Codox process exited with status:" exit)
      (System/exit exit))))

(defn deploy-all
  "Builds and deploys all sub-modules.

  :dry-run - install to local Maven repository, but do not deploy to remote."
  [{:keys [dry-run]}]
  (println "Deploying version" version (when dry-run "(dry run)") "...")
  (let [deploy? (not dry-run)
        sign-key-id (when deploy?
                      (or (System/getenv "CLOJARS_GPG_ID")
                          (throw (RuntimeException. "CLOJARS_GPG_ID environment variable not set"))))]
    (doseq [dir module-dirs]
      (println dir "...")
      (binding [b/*project-root* dir]
        (let [basis (b/create-basis)
              project-name (symbol "io.pedestal" (str "pedestal." dir))
              class-dir "target/classes"
              output-file (format "target/pedestal.%s-%s.jar" dir version)]
          (b/delete {:path "target"})
          (when (= "service" dir)
            ;; service is the only module that has Java compilation.
            (let [{:keys [exit]} (b/process {:command-args ["clojure" "-T:build" "compile-java"]})]
              (when-not (zero? exit)
                (println "Compilation failed with status:" exit)
                (System/exit exit))))
          (b/write-pom {:class-dir class-dir
                        :lib project-name
                        :version version
                        :basis basis
                        ;; pedestal the GitHub organization, then pedestal the multi-module project, then the sub-dir
                        :scm {:url (str "https://github.com/pedestal/pedestal/" dir)}})
          (b/copy-dir {:src-dirs ["src" "resources"]
                       :target-dir class-dir})
          (b/jar {:class-dir class-dir
                  :jar-file output-file})
          ;; Install it locally, so later modules can find it. This ensures that the
          ;; intra-project dependencies are correct in the generated POM files.
          (b/install {:basis basis
                      :lib project-name
                      :version version
                      :jar-file output-file
                      :class-dir class-dir})
          (when deploy?
            (d/deploy {:installer :remote
                       :artifact output-file
                       :pom-file (b/pom-path {:lib project-name
                                              :class-dir class-dir})
                       :sign-releases? true
                       :sign-key-id sign-key-id}))))))
  ;; TODO: That leiningen service-template
  )


(defn- workspace-dirty?
  []
  (not (str/blank? (b/git-process {:git-args "status -s"}))))

(defn update-version
  "Updates the version of the library.

  This changes the root VERSION.txt file and edits all deps.edn files to reflect the new version as well.

  :version (string, required) - new version number
  :commit (boolean, default true) - if true, then the workspace will be committed after changes; the workspace
  must also start clean
  :tag (boolean, default false) if true, then tag with the version number, after commit"
  [{:keys [version commit tag]
    :or {commit true
         tag true}}]
  (when (and commit
             (workspace-dirty?))
    (println "Error: workspace contains changes, those must be committed first")
    (System/exit 1))
  ;; Ensure the version number is parsable
  (v/parse-version version)
  (doseq [dir module-dirs]
    (println "Updating" dir "...")
    (requiring-invoke io.pedestal.build/update-version-in-deps dir version))

  ;; TODO: Do something for the lein service-template
  ;; Maybe update some of the docs as well?

  (b/write-file {:path version-file
                 :string version})

  (println "Updated to version:" version)

  (when commit
    (b/git-process {:git-args ["commit" "-a" "-m" (str "Advance to version " version)]})
    (println "Committed version change")
    (when tag
      (b/git-process {:git-args ["tag" version]})
      (println "Tagged commit"))))

(defn- validate
  [x f msg]
  (when (and (some? x)
             (not (f x)))
    (throw (IllegalArgumentException. msg))))

(defn advance-version
  "Advances the version number and updates VERSION.txt and deps.edn files. By default,
   the file changes are committed and tagged.

  Version numbers are of the form <major>.<minor>.<version>(-<stability>-<index>);
  the stability suffix is optional; the stability can be \"snapshot\", \"beta\", or \"rc\" (for release candidate).
  Version numbers without a stability suffix have a stability of :release.

  :level - :major, :minor, :patch, :snapshot, :beta, :rc, :release
  :dry-run - print new version number, but don't update
  :commit - see update-version
  :tag - see update-version

  :major, :minor, :patch increment their numbers and discard the stability suffix.
  So \"1.3.4-snapshot-2\" with level :minor would advance to \"1.4.0\".

  :release strips off the stability suffix so \"1.3.4-rc-3\" with level :release
  would advance to \"1.3.4\".  It's not valid to use level :release with a release version (one with
  not stability suffix).

  :snapshot, :beta, or :rc: If the existing stability matches, then the index number at the end is incremented,
  otherwise the stability is set to the level and the index is set to 1.

  \"1.3.4\" with level :snapshot becomes \"1.3.4-snapshot-1\".  \"1.3.4-beta-2\" with level :beta becomes
  \"1.3.4-beta-3\".


  Following a release, the pattern is `clj -T:build advance-version :level :patch :commit false` followed
  by `clj -T:build advance-version :level :snapshot`."
  [options]
  (let [{:keys [level dry-run]} options
        _ (validate level (set v/advance-levels)
                    (str ":level must be one of: "
                         (->> v/advance-levels (map name) (str/join ", "))))
        new-version (-> version v/parse-version (v/advance (or level :patch)) v/unparse-version)]
    (if dry-run
      (println "New version:" new-version)
      (update-version (-> options
                          (dissoc :level :dry-run)
                          (assoc :version new-version))))))
