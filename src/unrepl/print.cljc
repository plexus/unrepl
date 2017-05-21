(ns unrepl.print
  (:require [clojure.string :as str]
            #?@(:clj  ([clojure.edn :as edn])
                :cljs ([goog.string.format]
                       [cljs.reader :as edn]
                       [cljs.core :refer [TaggedLiteral Namespace]])))
  (:import #?@(:clj  ([clojure.lang TaggedLiteral Namespace]
                      [java.io StringWriter])
               :cljs ([goog.date DateTime]
                      [goog.string StringBuffer]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clojure/ClojureScript compatibility

#?@(:cljs
    ((def format goog.string/format)

     (def Throwable js/Error)

     (def Object js/Object)

     (defn Throwable->map [e]
       {:message (.-message e)
        :trace (str/split (.-stack e) "\n")})))

(def MAX_INT #?(:clj Long/MAX_VALUE
                :cljs (.-MAX_VALUE js/Number)))

(defn pending? [x]
  #?(:clj
     ;; borrowed from https://github.com/brandonbloom/fipp/blob/8df75707e355c1a8eae5511b7d73c1b782f57293/src/fipp/ednize.clj#L37-L51
     (and (instance? clojure.lang.IPending x)
          (not (.isRealized ^clojure.lang.IPending x)))

     :cljs
     ;; borrowed from cljs.pprint
     (and (satisfies? IPending x) (not (-realized? x)))))

(defn string-buffer []
  #?(:clj (StringWriter.)
     :cljs (StringBuffer.)))

(defn buffer-write [#?(:clj ^StringWriter this
                       :cljs ^StringBuffer this) ^String s]
  #?(:clj (.write this s)
     :cljs (.append this s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *elide* (constantly nil))

(deftype ElidedKVs [s]
  #?@(:clj
      (clojure.lang.Seqable
       (seq [_] (seq s)))

      :cljs
      (cljs.core/ISeqable
       (-seq [_] (seq s)))))

(def atomic? (some-fn nil? true? false? char? string? symbol? keyword? #(and (number? %) #?(:clj (not (ratio? %))))))

(defn- as-str
  "Like pr-str but escapes all ASCII conytrol chars."
  [x]
                                        ;hacky
  (cond
    (string? x) (str/replace (pr-str x) #"\p{Cntrl}"
                             #(format "\\u%04x" (int (.charAt ^String % 0))))
    (char? x) (str/replace (pr-str x) #"\p{Cntrl}"
                           #(format "u%04x" (int (.charAt ^String % 0))))
    :else (pr-str x)))

;; Is this dead code?
(defn- insert-class [classes ^Class class]
  (let [ancestor-or-self? #(.isAssignableFrom ^Class % class)]
    (-> []
        (into (remove ancestor-or-self?) classes)
        (conj class)
        (into (filter ancestor-or-self?) classes))))

(def ^:dynamic *attach* nil)

(defmacro ^:private latent-fn [& fn-body]
  `(let [d# (delay (binding [*ns* (find-ns '~(ns-name *ns*))] (eval '(fn ~@fn-body))))]
     (fn
       ([] (@d#))
       ([x#] (@d# x#))
       ([x# & xs#] (apply @d# x# xs#)))))

(defn- as-inst [x]
  (let [readers {'inst #(tagged-literal 'inst %)}]
    #?(:clj
       (edn/read-string {:readers readers} (pr-str x))

       :cljs
       (binding [edn/*tag-table* (atom (merge @edn/*tag-table* readers))]
         (edn/read-string (pr-str x))))))

(def ^:dynamic *object-representations*
  "map of classes to functions returning their representation component (3rd item in #unrepl/object [class id rep])"
  #?(:clj
     {clojure.lang.IDeref
      (fn [x]
        (let [[ex val] (when-not (pending? x)
                         (try [false @x]
                              (catch Throwable e
                                [true e])))
              failed? (or ex (and (instance? clojure.lang.Agent x)
                                  (agent-error x)))
              status (cond
                       failed? :failed
                       pending? :pending
                       :else :ready)]
          {:unrepl.ref/status status :unrepl.ref/val val}))

      java.io.File (fn [^java.io.File f]
                     (into {:path (.getPath f)}
                           (when (and *attach* (.isFile f))
                             {:attachment (tagged-literal 'unrepl/mime
                                                          (into {:content-type "application/octet-stream"
                                                                 :content-length (.length f)}
                                                                (*attach* #(java.io.FileInputStream. f))))})))

      java.awt.Image (latent-fn [^java.awt.Image img]
                                (let [w (.getWidth img nil)
                                      h (.getHeight img nil)]
                                  (into {:width w, :height h}
                                        (when *attach*
                                          {:attachment
                                           (tagged-literal 'unrepl/mime
                                                           (into {:content-type "image/png"}
                                                                 (*attach* #(let [bos (java.io.ByteArrayOutputStream.)]
                                                                              (when (javax.imageio.ImageIO/write
                                                                                     (doto (java.awt.image.BufferedImage. w h java.awt.image.BufferedImage/TYPE_INT_ARGB)
                                                                                       (-> .getGraphics (.drawImage img 0 0 nil)))
                                                                                     "png" bos)
                                                                                (java.io.ByteArrayInputStream. (.toByteArray bos)))))))}))))

      Object (fn [x]
               (if (-> x class .isArray)
                 (seq x)
                 (str x)))}

     :cljs {;;TODO fill this in
            js/Object (fn [x]
                        (if (array?)
                          (seq x)
                          (str x)))}))

(defn- object-representation [x]
  (reduce-kv (fn [_ class f]
               (when (instance? class x) (reduced (f x)))) nil *object-representations*)) ; todo : cache

(defmulti default-ednize-fn class)

(defmethod default-ednize-fn TaggedLiteral [x] x)

#?(:clj
   (defmethod default-ednize-fn clojure.lang.Ratio [^clojure.lang.Ratio x]
     (tagged-literal 'unrepl/ratio [(.numerator x) (.denominator x)])))

(defmethod default-ednize-fn Throwable [t]
  (tagged-literal 'error (Throwable->map t)))

(defn- class-form [^Class x]
  (if (.isArray x) [(-> x .getComponentType class-form)] (symbol (.getName x))))

#?@(:clj
    ((defmethod default-ednize-fn Class [x]
       (tagged-literal 'unrepl.java/class (class-form x)))

     (defmethod default-ednize-fn java.util.Calendar [x]
       (as-inst x))

     (defmethod default-ednize-fn java.util.Date [x]
       (as-inst x))

     (defmethod default-ednize-fn java.sql.Timestamp [x]
       (as-inst x))))

#?@(:cljs
    ((defmethod default-ednize-fn js/Date [x]
       (as-inst x))

     (defmethod default-ednize-fn DateTime [x]
       (as-inst (.-date x)))))

(defmethod default-ednize-fn Namespace [x]
  (tagged-literal 'unrepl/ns (ns-name x)))

;; Note that clj/cljs behave different here.
;; :clj  (str #"x") => "x"
;; :cljs (str #"x") => "/x/"
(defmethod default-ednize-fn #?(:clj java.util.regex.Pattern
                                :cljs js/RegExp) [x]
  (tagged-literal 'unrepl/pattern (str x)))


(defmethod default-ednize-fn Object [x]
  (tagged-literal 'unrepl/object
                  #?(:clj
                     [(class x)
                      (format "0x%x" (System/identityHashCode x))
                      (object-representation x)
                      {:bean {(tagged-literal 'unrepl/... (*elide* (ElidedKVs. (bean x))))
                              (tagged-literal 'unrepl/... nil)}}]
                     :cljs
                     [(type x)
                      (object-representation x)]))


  )

(def ^:dynamic *ednize* default-ednize-fn)

(def ^:dynamic *realize-on-print*
  "Set to false to avoid realizing lazy sequences."
  true)

(defmacro ^:private blame-seq [& body]
  `(try (seq ~@body)
        (catch Throwable t#
          (list (tagged-literal 'unrepl/lazy-error t#)))))

(defn- may-print? [s]
  (or *realize-on-print* (pending? s) (realized? s)))

(defn- elide-vs [vs print-length]
  (cond
    (pos? print-length)
    (lazy-seq
     (if (may-print? vs)
       (if-some [[v :as vs] (blame-seq vs)]
         (cons v (elide-vs (rest vs) (dec print-length)))
         ())
       (list (tagged-literal 'unrepl/... (*elide* vs)))))
    (and (may-print? vs) (nil? (blame-seq vs))) ()
    :else (list (tagged-literal 'unrepl/... (*elide* vs)))))

(defn- elide-kvs [kvs print-length]
  (if-some [more-kvs (when print-length (seq (drop print-length kvs)))]
    (concat (take print-length kvs) [[(tagged-literal 'unrepl/... (*elide* (ElidedKVs. more-kvs))) (tagged-literal 'unrepl/... nil)]])
    kvs))

(defn ednize "Shallow conversion to edn safe subset."
  ([x] (ednize x *print-length* *print-meta*))
  ([x print-length] (ednize x print-length *print-meta*))
  ([x print-length print-meta]
   (cond
     (atomic? x) x
     (and print-meta (meta x)) (tagged-literal 'unrepl/meta [(meta x) (ednize x print-length false)])
     (map? x) (into {} (elide-kvs x print-length))
     (instance? ElidedKVs x) (ElidedKVs. (elide-kvs x print-length))
     #?@(:clj ((instance? clojure.lang.MapEntry x) x))
     (vector? x) (into (empty x) (elide-vs x print-length))
     (seq? x) (elide-vs x print-length)
     (set? x) (into #{} (elide-vs x print-length))
     :else (let [x' (*ednize* x)]
             (if (= x x')
               x
               (recur x' print-length print-meta)))))) ; todo : cache

(declare print-on)

(defn- print-vs
  ([write vs rem-depth]
    (print-vs write vs rem-depth print-on " "))
  ([write vs rem-depth print-v sep]
    (when-some [[v & vs] (seq vs)]
      (print-v write v rem-depth)
      (doseq [v vs]
        (write sep)
        (print-v write v rem-depth)))))

(defn- print-kv [write [k v] rem-depth]
  (print-on write k rem-depth)
  (write " ")
  (print-on write v rem-depth))

(defn- print-kvs [write kvs rem-depth]
    (print-vs write kvs rem-depth print-kv ", "))

(defn- print-on [write x rem-depth]
  (let [rem-depth (dec rem-depth)
        x (ednize x (if (neg? rem-depth) 0 *print-length*))]
    (cond
      (tagged-literal? x)
      (do (write (str "#" (:tag x) " "))
          (case (:tag x)
            unrepl/... (binding ; don't elide the elision
                           [*print-length* MAX_INT]
                         (print-on write (:form x) MAX_INT))
            (recur write (:form x) rem-depth)))
      (or (map? x) (instance? ElidedKVs x)) (do (write "{") (print-kvs write x rem-depth) (write "}"))
      (vector? x) (do (write "[") (print-vs write x rem-depth) (write "]"))
      (seq? x) (do (write "(") (print-vs write x rem-depth) (write ")"))
      (set? x) (do (write "#{") (print-vs write x rem-depth) (write "}"))
      (atomic? x) (write (as-str x))
      :else (throw (ex-info "Can't print value." {:value x})))))

(defn edn-str [x]
  (let [out (string-buffer)
        write (fn [^String s] (buffer-write out s))]
    (binding [*print-readably* true
              *print-length* (or *print-length* 10)]
      (print-on write x (or *print-level* 8))
      (str out))))

(defn full-edn-str [x]
  (binding [*print-length* MAX_INT
            *print-level* MAX_INT]
    (edn-str x)))
