(ns lightmod.app
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [nightcode.state :refer [pref-state runtime-state]]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.utils :as u]
            [cljs.build.api :refer [build]]
            [hawk.core :as hawk]
            [lightmod.reload :as lr]
            [cljs.analyzer :as ana])
  (:import [javafx.application Platform]
           [javafx.scene.control Button ContentDisplay Label]
           [javafx.scene.image ImageView]
           [javafx.event EventHandler]
           [javafx.fxml FXMLLoader]))

(defn dir-pane [f]
  (let [pane (FXMLLoader/load (io/resource "dir.fxml"))]
    (shortcuts/add-tooltips! pane [:#up :#new_file :#open_in_file_browser :#close])
    (doseq [file (.listFiles f)
            :when (and  (-> file .getName (.startsWith ".") not)
                        (-> file .getName (not= "main.js")))]
      (-> (.lookup pane "#filegrid")
          .getContent
          .getChildren
          (.add (doto (if-let [icon (u/get-icon-path file)]
                        (Button. "" (doto (Label. (.getName file)
                                            (doto (ImageView. icon)
                                              (.setFitWidth 90)
                                              (.setPreserveRatio true)))
                                      (.setContentDisplay ContentDisplay/TOP)))
                        (Button. (.getName file)))
                  (.setPrefWidth 150)
                  (.setPrefHeight 150)
                  (.setOnAction (reify EventHandler
                                  (handle [this event]
                                    (swap! pref-state assoc :selection (.getCanonicalPath file)))))))))
    pane))

(defn get-project-dir
  ([] (get-project-dir (io/file (:selection @pref-state))))
  ([file]
   (loop [f file]
     (when-let [parent (.getParentFile f)]
       (if (= parent (:projects-dir @runtime-state))
         f
         (recur parent))))))

(defn eval-cljs-code [path dir code]
  (when-let [{:keys [pane eval-chan]} (get-in @runtime-state [:projects dir])]
    (when-let [app (.lookup pane "#app")]
      (-> (.getEngine app)
          (.executeScript "lightmod.init")
          (.call "eval_code" (into-array [path code])))
      nil)))

(defn set-selection-listener! [scene]
  (add-watch pref-state :selection-changed
    (fn [_ _ _ {:keys [selection]}]
      (when selection
        (let [file (io/file selection)]
          (when-let [project-dir (get-project-dir file)]
            (when-let [tab (->> (.lookup scene "#projects")
                                .getTabs
                                (filter #(= (.getText %) (.getName project-dir)))
                                first)]
              (when-let [pane (or (when (.isDirectory file)
                                    (dir-pane file))
                                  (get-in @runtime-state [:editor-panes selection])
                                  (when-let [new-editor (e/editor-pane pref-state runtime-state file
                                                          (case (-> file .getName u/get-extension)
                                                            ("clj" "cljc") e/eval-code
                                                            "cljs" (partial eval-cljs-code selection (.getCanonicalPath project-dir))
                                                            nil))]
                                    (swap! runtime-state update :editor-panes assoc selection new-editor)
                                    new-editor))]
                (let [content (.getContent tab)
                      editors (-> content
                                  (.lookup "#project")
                                  .getItems
                                  (.get 1)
                                  (.lookup "#editors"))]
                  (shortcuts/hide-tooltips! content)
                  (doto (.getChildren editors)
                    (.clear)
                    (.add pane))
                  (.setDisable (.lookup editors "#up") (= selection (.getCanonicalPath project-dir)))
                  (.setDisable (.lookup editors "#close") (.isDirectory file))
                  (Platform/runLater
                    (fn []
                      (some-> (.lookup pane "#webview") .requestFocus))))))))))))

(defn copy-from-resources! [from to]
  (let [dest (io/file to ".out" from)]
    (when-not (.exists dest)
      (.mkdirs (.getParentFile dest))
      (spit dest (slurp (io/resource from))))
    (str (-> to io/file .getName) "/.out/" from)))

(defn sanitize-name [s]
  (-> s
      str/lower-case
      (str/replace #"[ _]" "-")
      (str/replace #"[^a-z0-9\-]" "")))

(defn path->ns [path leaf-name]
  (-> path io/file .getName sanitize-name (str "." leaf-name)))

(defn compile-clj! [dir file-path]
  (try
    (load-file file-path)
    (lr/send-message! dir {:type :visual-clj})
    (catch Exception e
      (lr/send-message! dir {:type :visual-clj
                             :exception (merge
                                          {:message (.getMessage e)}
                                          (select-keys (ex-data e) [:line :column]))}))))

(defn compile-cljs! [dir]
  (let [cljs-dir (io/file dir ".out" (-> dir io/file .getName))
        warnings (atom [])
        on-warning (fn [warning-type env extra]
                     (swap! warnings conj
                       (merge {:message (ana/error-message warning-type extra)
                               :ns (-> env :ns :name)
                               :type warning-type
                               :file ana/*cljs-file*}
                         (select-keys env [:line :column]))))]
    (when (.exists cljs-dir)
      (u/delete-children-recursively! cljs-dir))
    (try
      (build dir
        {:output-to (.getCanonicalPath (io/file dir "main.js"))
         :output-dir (.getCanonicalPath (io/file dir ".out"))
         :main (path->ns dir "client")
         :asset-path ".out"
         :preloads '[lightmod.init]
         :foreign-libs (mapv #(update % :file copy-from-resources! dir)
                         [{:file "js/p5.js"
                           :provides ["p5.core"]}
                          {:file "js/p5.tiledmap.js"
                           :provides ["p5.tiled-map"]
                           :requires ["p5.core"]}
                          {:file "cljsjs/react/development/react.inc.js"
                           :provides ["react" "cljsjs.react"]
                           :file-min ".out/cljsjs/react/production/react.min.inc.js"
                           :global-exports '{react React}}
                          {:file "cljsjs/create-react-class/development/create-react-class.inc.js"
                           :provides ["cljsjs.create-react-class" "create-react-class"]
                           :requires ["react"]
                           :file-min "cljsjs/create-react-class/production/create-react-class.min.inc.js"
                           :global-exports '{create-react-class createReactClass}}
                          {:file "cljsjs/react-dom/development/react-dom.inc.js"
                           :provides ["react-dom" "cljsjs.react.dom"]
                           :requires ["react"]
                           :file-min "cljsjs/react-dom/production/react-dom.min.inc.js"
                           :global-exports '{react-dom ReactDOM}}])
         :externs (mapv #(copy-from-resources! % dir)
                    ["cljsjs/react/common/react.ext.js"
                     "cljsjs/create-react-class/common/create-react-class.ext.js"
                     "cljsjs/react-dom/common/react-dom.ext.js"])
         :warning-handlers [on-warning]})
      (lr/send-message! dir {:type :visual
                             :warnings @warnings})
      (catch Exception e
        (lr/send-message! dir {:type :visual
                               :warnings @warnings
                               :exception (merge
                                            {:message (.getMessage e)}
                                            (select-keys (ex-data e) [:line :column]))})))))

(defn stop-server! [dir]
  (when-let [{:keys [server reload-stop-fn reload-file-watcher]}
             (get-in @runtime-state [:projects dir])]
    (.stop server)
    (reload-stop-fn)
    (hawk/stop! reload-file-watcher)))

(definterface Bridge
  (onevalcomplete [path results ns-name]))

(defn start-server! [project-pane dir]
  (stop-server! dir)
  (compile-clj! dir (.getCanonicalPath (io/file dir "server.clj")))
  (let [-main (resolve (symbol (path->ns dir "server") "-main"))
        server (-main)
        port (-> server .getConnectors (aget 0) .getLocalPort)
        url (str "http://localhost:" port "/"
                 (-> dir io/file .getName)
                 "/index.html")
        reload-stop-fn (lr/start-reload-server! dir)
        reload-port (-> reload-stop-fn meta :local-port)
        out-dir (.getCanonicalPath (io/file dir ".out"))
        bridge (reify Bridge
                 (onevalcomplete [this path results ns-name]
                   (when-let [editor (get-in @runtime-state [:editor-panes path])]
                     (-> editor
                         (.lookup "#webview")
                         .getEngine
                         (.executeScript "window")
                         (.call "setInstaRepl" (into-array [results]))))))]
    (spit (io/file dir ".out/reload-port.txt") (str reload-port))
    (Platform/runLater
      (fn []
        (-> project-pane
            (.lookup "#app")
            .getEngine
            (.executeScript "window")
            (.setMember "java" bridge))))
    (swap! runtime-state assoc-in [:projects dir]
      {:pane project-pane
       :url url
       :server server
       :bridge bridge
       :reload-stop-fn reload-stop-fn
       :clients #{}
       :editor-file-watcher (or (get-in @runtime-state [:projects dir :editor-file-watcher])
                                (e/create-file-watcher dir runtime-state))
       :reload-file-watcher
       (hawk/watch! [{:paths [dir]
                      :handler (fn [ctx {:keys [kind file]}]
                                 (when (and (= kind :modify)
                                            (some #(-> file .getName (.endsWith %)) [".clj" ".cljc"]))
                                   (compile-clj! dir (.getCanonicalPath file)))
                                 (cond
                                   (and (some #(-> file .getName (.endsWith %)) [".cljs" ".cljc"])
                                        (not (u/parent-path? out-dir (.getCanonicalPath file))))
                                   (compile-cljs! dir)
                                   (u/parent-path? out-dir (.getCanonicalPath file))
                                   (lr/reload-file! dir file))
                                 ctx)}])})
    url))

(defn stop-app! [project-pane dir]
  (stop-server! dir)
  (doto (.lookup project-pane "#app")
    (-> .getEngine (.loadContent "<html><body></body></html>"))))

(defn start-app! [project-pane dir]
  (-> (fn []
        (compile-cljs! dir)
        (let [url (start-server! project-pane dir)]
          (Platform/runLater
            (fn []
              (doto (.lookup project-pane "#app")
                (.setContextMenuEnabled false)
                (-> .getEngine (.load url)))))))
      (Thread.)
      .start))

