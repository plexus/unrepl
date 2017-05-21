(ns leiningen.unrepl-make-blob
  (:require [unrepl.filter-cljc :refer [filter-cljs]]))

(defn unrepl-make-blob [project]
  (let [code (str (slurp "src/unrepl/print.cljc") (slurp "src/unrepl/repl.clj") "\n(unrepl.repl/start)")]
    (-> "resources/unrepl" java.io.File. .mkdirs)
    (spit "resources/unrepl/blob.clj"
          (prn-str
           `(let [prefix# (name (gensym))
                  code# (.replaceAll ~code "unrepl\\.(?:repl|print)" (str "$0" prefix#))
                  rdr# (-> code# java.io.StringReader. clojure.lang.LineNumberingPushbackReader.)]
              (try
                (binding [*ns* *ns*]
                  (loop [ret# nil]
                    (let [form# (read rdr# false 'eof#)]
                      (if (= 'eof# form#)
                        ret#
                        (recur (eval form#))))))
                (catch Throwable t#
                  (println "[:unrepl.upgrade/failed]")
                  (throw t#))))))

    (spit "resources/unrepl/blob_lumo.cljs"
          (prn-str
           (let [prefix (name (gensym))
                 code (.replaceAll code "unrepl\\.(?:repl|print)" (str "$0" prefix))
                 code (filter-cljs code)]
             `(try
                (cljs.js/eval-str @#'lumo.repl/st
                                  ~code
                                  nil
                                  (lumo.repl/make-eval-opts)
                                  (fn [{:keys [~'error]}]
                                    (when ~'error
                                      (println ~'error)
                                      (println "[:unrepl.upgrade/failed 1]"))))
                (catch ~'js/Error t#
                  (println t#)
                  (println "[:unrepl.upgrade/failed 2]")
                  (throw t#))))))))
