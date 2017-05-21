(ns unrepl.filter-cljc
  (:refer-clojure :exclude [reader-conditional?])
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [rewrite-clj.node.token :as t]
            [rewrite-clj.zip :as z]))

(defn reader-conditional? [form]
  (and (= (z/tag form) :reader-macro)
       (= (first (z/down form)) (t/token-node '?))))

(defn reader-conditional-splice? [form]
  (and (= (z/tag form) :reader-macro)
       (= (first (z/down form)) (t/token-node (symbol "?@")))))

(defn apply-reader-conditional
  "`form` is a rewrite-clj parsed form of a reader conditional. `type` is either
  `:clj` or `:cljs`."
  [type form]
  (->> form
       n/children
       second
       n/children
       (remove n/whitespace-or-comment?)
       (partition 2)
       (map (fn [[t v]]
              [(n/value t) v]))
       (into {})
       (#(get % type))))


(defn zip-apply-reader-conditional [type z]
  (z/replace z (apply-reader-conditional type (first z))))

(defn filter-rc-zipper [type z]
  (-> z
      (z/postwalk reader-conditional?
                  (partial zip-apply-reader-conditional type))
      (z/postwalk reader-conditional-splice?
                  (comp
                   z/splice
                   (partial zip-apply-reader-conditional type)))))

(defn filter-clj [code]
  (z/string (filter-rc-zipper :clj (z/of-string code))))

(defn filter-cljs [code]
  (z/string (-> (filter-rc-zipper :cljs (z/of-string (str "(do " code ")")))
                (z/next)
                (z/next))))
