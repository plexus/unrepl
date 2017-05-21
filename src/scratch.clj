
(def form (p/parse-string "#?@{:clj [1 2 3]
:cljs 2}"))


(n/children (n/forms-node '(1)))


;;z/of-file




(postwalk zloc p? f)
