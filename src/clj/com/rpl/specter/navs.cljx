(ns com.rpl.specter.navs
  (:use [com.rpl.specter macros]
        [com.rpl.specter.util-macros :only [doseqres]])
  (:require [com.rpl.specter [impl :as i]]
            [clojure [walk :as walk]])
  )

(defn- append [coll elem]
  (-> coll vec (conj elem)))

(defn not-selected?*
  [compiled-path structure]
  (->> structure
       (i/compiled-select-any* compiled-path)
       (identical? NONE)))

(defn selected?*
  [compiled-path structure]
  (not (not-selected?* compiled-path structure)))

(defn walk-select [pred continue-fn structure]
  (let [ret (i/mutable-cell NONE)
        walker (fn this [structure]
                 (if (pred structure)
                   (let [r (continue-fn structure)]
                     (if-not (identical? r NONE)
                       (set-cell! ret r))
                     r
                     )
                   (walk/walk this identity structure)
                   ))]
    (walker structure)
    (get-cell ret)
    ))

(defn key-select [akey structure next-fn]
  (next-fn (get structure akey)))

(defn key-transform [akey structure next-fn]
  (assoc structure akey (next-fn (get structure akey))
  ))

(defn all-select [structure next-fn]
  (doseqres NONE [e structure]
    (next-fn e)))

#+cljs
(defn queue? [coll]
  (= (type coll) (type #queue [])))

#+clj
(defn queue? [coll]
  (instance? clojure.lang.PersistentQueue coll))

(defprotocol AllTransformProtocol
  (all-transform [structure next-fn]))

(defn- non-transient-map-all-transform [structure next-fn empty-map]
  (reduce-kv
    (fn [m k v]
      (let [[newk newv] (next-fn [k v])]
        (assoc m newk newv)
        ))
    empty-map
    structure
    ))

(extend-protocol AllTransformProtocol
  nil
  (all-transform [structure next-fn]
    nil
    )

  ;; in cljs they're PersistentVector so don't need a special case
  #+clj clojure.lang.MapEntry
  #+clj
  (all-transform [structure next-fn]
    (let [newk (next-fn (key structure))
          newv (next-fn (val structure))]
      (clojure.lang.MapEntry. newk newv)
      ))

  #+clj clojure.lang.PersistentVector #+cljs cljs.core/PersistentVector
  (all-transform [structure next-fn]
    (mapv next-fn structure))

  #+clj
  clojure.lang.PersistentArrayMap
  #+clj
  (all-transform [structure next-fn]
    (let [k-it (.keyIterator structure)
          v-it (.valIterator structure)]
      (loop [ret {}]
        (if (.hasNext k-it)
          (let [k (.next k-it)
                v (.next v-it)
                [newk newv] (next-fn [k v])]
            (recur (assoc ret newk newv)))
          ret
          ))))

  #+cljs
  cljs.core/PersistentArrayMap
  #+cljs
  (all-transform [structure next-fn]
    (non-transient-map-all-transform structure next-fn {})
    )

  #+clj clojure.lang.PersistentTreeMap #+cljs cljs.core/PersistentTreeMap
  (all-transform [structure next-fn]
    (non-transient-map-all-transform structure next-fn (empty structure))
    )

  #+clj clojure.lang.PersistentHashMap #+cljs cljs.core/PersistentHashMap
  (all-transform [structure next-fn]
    (persistent!
      (reduce-kv
        (fn [m k v]
          (let [[newk newv] (next-fn [k v])]
            (assoc! m newk newv)
            ))
        (transient
          #+clj clojure.lang.PersistentHashMap/EMPTY #+cljs cljs.core.PersistentHashMap.EMPTY
          )
        structure
        )))


  #+clj
  Object
  #+clj
  (all-transform [structure next-fn]
    (let [empty-structure (empty structure)]
      (cond (and (list? empty-structure) (not (queue? empty-structure)))
            ;; this is done to maintain order, otherwise lists get reversed
            (doall (map next-fn structure))

            (map? structure)
            ;; reduce-kv is much faster than doing r/map through call to (into ...)
            (reduce-kv
              (fn [m k v]
                (let [[newk newv] (next-fn [k v])]
                  (assoc m newk newv)
                  ))
              empty-structure
              structure
              )

            :else
            (->> structure (r/map next-fn) (into empty-structure))
        )))

  #+cljs
  default
  #+cljs 
  (all-transform [structure next-fn]
    (let [empty-structure (empty structure)]
      (if (and (list? empty-structure) (not (queue? empty-structure)))
        ;; this is done to maintain order, otherwise lists get reversed
        (doall (map next-fn structure))
        (into empty-structure (map #(next-fn %)) structure)
        )))
  )

(defprotocol MapValsTransformProtocol
  (map-vals-transform [structure next-fn]))

(defn map-vals-non-transient-transform [structure empty-map next-fn]
  (reduce-kv
    (fn [m k v]
      (assoc m k (next-fn v)))
    empty-map
    structure))

(extend-protocol MapValsTransformProtocol
  nil
  (map-vals-transform [structure next-fn]
    nil
    )

  #+clj
  clojure.lang.PersistentArrayMap
  #+clj
  (map-vals-transform [structure next-fn]
    (let [k-it (.keyIterator structure)
          v-it (.valIterator structure)]
      (loop [ret {}]
        (if (.hasNext k-it)
          (let [k (.next k-it)
                v (.next v-it)]
            (recur (assoc ret k (next-fn v))))
          ret
          ))))

  #+cljs
  cljs.core/PersistentArrayMap
  #+cljs
  (map-vals-transform [structure next-fn]
    (map-vals-non-transient-transform structure {} next-fn)
    )

  #+clj clojure.lang.PersistentTreeMap #+cljs cljs.core/PersistentTreeMap
  (map-vals-transform [structure next-fn]
    (map-vals-non-transient-transform structure (empty structure) next-fn)
    )

  #+clj clojure.lang.PersistentHashMap #+cljs cljs.core/PersistentHashMap
  (map-vals-transform [structure next-fn]
    (persistent!
      (reduce-kv
        (fn [m k v]
          (assoc! m k (next-fn v)))
        (transient
          #+clj clojure.lang.PersistentHashMap/EMPTY #+cljs cljs.core.PersistentHashMap.EMPTY
          )
        structure
        )))

  #+clj Object #+cljs default
  (map-vals-transform [structure next-fn]
    (reduce-kv
      (fn [m k v]
        (assoc m k (next-fn v)))
      (empty structure)
      structure))
  )

(defn srange-select [structure start end next-fn]
  (next-fn (-> structure vec (subvec start end))))

(defn srange-transform [structure start end next-fn]
  (let [structurev (vec structure)
        newpart (next-fn (-> structurev (subvec start end)))
        res (concat (subvec structurev 0 start)
                    newpart
                    (subvec structurev end (count structure)))]
    (if (vector? structure)
      (vec res)
      res
      )))

(defn- matching-indices [aseq p]
  (keep-indexed (fn [i e] (if (p e) i)) aseq))

(defn matching-ranges [aseq p]
  (first
    (reduce
      (fn [[ranges curr-start curr-last :as curr] i]
        (cond
          (nil? curr-start)
          [ranges i i]

          (= i (inc curr-last))
          [ranges curr-start i]

          :else
          [(conj ranges [curr-start (inc curr-last)]) i i]
          ))
      [[] nil nil]
      (concat (matching-indices aseq p) [-1])
    )))

(defn extract-basic-filter-fn [path]
  (cond (fn? path)
        path

        (and (coll? path)
             (every? fn? path))
        (reduce
          (fn [combined afn]
            (fn [structure]
              (and (combined structure) (afn structure))
              ))
          path
          )))



(defn if-select [params params-idx vals structure next-fn then-tester then-nav then-params else-nav]
  (let [test? (then-tester structure)
        sel (if test?
              then-nav
              else-nav)
        idx (if test? params-idx (+ params-idx then-params))]
    (i/exec-rich-select*
         sel
         params
         idx
         vals
         structure
         next-fn
         )))

(defn if-transform [params params-idx vals structure next-fn then-tester then-nav then-params else-nav]
  (let [test? (then-tester structure)
        tran (if test?
               then-nav
               else-nav)
        idx (if test? params-idx (+ params-idx then-params))]
    (i/exec-rich-transform*
      tran
      params
      idx
      vals
      structure
      next-fn
      )))

(defn terminal* [params params-idx vals structure]
  (let [afn (aget ^objects params params-idx)]
    (if (identical? vals [])
      (afn structure)
      (apply afn (conj vals structure)))
      ))

(defn filter-select [afn structure next-fn]
  (if (afn structure)
    (next-fn structure)
    NONE))

(defn filter-transform [afn structure next-fn]
  (if (afn structure)
    (next-fn structure)
    structure))



(defnav PosNavigator [getter updater]
  (select* [this structure next-fn]
    (if-not (i/fast-empty? structure)
      (next-fn (getter structure))
      NONE))
  (transform* [this structure next-fn]
    (if (i/fast-empty? structure)
      structure
      (updater structure next-fn))))

(defprotocol AddExtremes
  (append-all [structure elements])
  (prepend-all [structure elements]))

(extend-protocol AddExtremes
  nil
  (append-all [_ elements]
    elements)
  (prepend-all [_ elements]
    elements)

  #+clj clojure.lang.PersistentVector #+cljs cljs.core/PersistentVector
  (append-all [structure elements]
    (reduce conj structure elements))
  (prepend-all [structure elements]
    (let [ret (transient [])]
      (as-> ret <>
            (reduce conj! <> elements)
            (reduce conj! <> structure)
            (persistent! <>)
            )))

  #+clj Object #+cljs default
  (append-all [structure elements]
    (concat structure elements))
  (prepend-all [structure elements]
    (concat elements structure))
  )


(defprotocol UpdateExtremes
  (update-first [s afn])
  (update-last [s afn]))

(defprotocol GetExtremes
  (get-first [s])
  (get-last [s]))

(defprotocol FastEmpty
  (fast-empty? [s]))

(defn- update-first-list [l afn]
  (cons (afn (first l)) (rest l)))

(defn- update-last-list [l afn]
  (append (butlast l) (afn (last l))))

#+clj
(defn vec-count [^clojure.lang.IPersistentVector v]
  (.length v))

#+cljs
(defn vec-count [v]
  (count v))

#+clj
(defn transient-vec-count [^clojure.lang.ITransientVector v]
  (.count v))

#+cljs
(defn transient-vec-count [v]
  (count v))

(extend-protocol UpdateExtremes
  #+clj clojure.lang.PersistentVector #+cljs cljs.core/PersistentVector
  (update-first [v afn]
    (let [val (nth v 0)]
      (assoc v 0 (afn val))
      ))
  (update-last [v afn]
    ;; type-hinting vec-count to ^int caused weird errors with case
    (let [c (int (vec-count v))]
      (case c
        1 (let [[e] v] [(afn e)])
        2 (let [[e1 e2] v] [e1 (afn e2)])
        (let [i (dec c)]
          (assoc v i (afn (nth v i)))
          ))))
  #+clj Object #+cljs default
  (update-first [l val]
    (update-first-list l val))
  (update-last [l val]
    (update-last-list l val)
    ))

(extend-protocol GetExtremes
  #+clj clojure.lang.IPersistentVector #+cljs cljs.core/PersistentVector
  (get-first [v]
    (nth v 0))
  (get-last [v]
    (peek v))
  #+clj Object #+cljs default
  (get-first [s]
    (first s))
  (get-last [s]
    (last s)
    ))


(extend-protocol FastEmpty
  nil
  (fast-empty? [_] true)

  #+clj clojure.lang.IPersistentVector #+cljs cljs.core/PersistentVector
  (fast-empty? [v]
    (= 0 (vec-count v)))
  #+clj clojure.lang.ITransientVector #+cljs cljs.core/TransientVector
  (fast-empty? [v]
    (= 0 (transient-vec-count v)))
  #+clj Object #+cljs default
  (fast-empty? [s]
    (empty? s))
  )

(defn walk-until [pred on-match-fn structure]
  (if (pred structure)
    (on-match-fn structure)
    (walk/walk (partial walk-until pred on-match-fn) identity structure)
    ))

(defn fn-invocation? [f]
  (or #+clj  (instance? clojure.lang.Cons f)
      #+clj  (instance? clojure.lang.LazySeq f)
      #+cljs (instance? cljs.core.LazySeq f)
      (list? f)))

(defn codewalk-until [pred on-match-fn structure]
  (if (pred structure)
    (on-match-fn structure)
    (let [ret (walk/walk (partial codewalk-until pred on-match-fn) identity structure)]
      (if (and (fn-invocation? structure) (fn-invocation? ret))
        (with-meta ret (meta structure))
        ret
        ))))


(def collected?*
  (i/->ParamsNeededPath
    (reify i/RichNavigator
      (rich-select* [this params params-idx vals structure next-fn]
        (let [afn (aget ^objects params params-idx)]
          (if (afn vals)
            (next-fn params (inc params-idx) vals structure)
            NONE
            )))
      (rich-transform* [this params params-idx vals structure next-fn]
        (let [afn (aget ^objects params params-idx)]
          (if (afn vals)
            (next-fn params (inc params-idx) vals structure)
            structure
            ))))
    1
    ))

(def DISPENSE*
  (i/no-params-rich-compiled-path
    (reify i/RichNavigator
      (rich-select* [this params params-idx vals structure next-fn]
        (next-fn params params-idx [] structure))
      (rich-transform* [this params params-idx vals structure next-fn]
        (next-fn params params-idx [] structure)))))


