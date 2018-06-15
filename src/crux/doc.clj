(ns crux.doc
  (:require [clojure.tools.logging :as log]
            [clojure.set :as set]
            [crux.byte-utils :as bu]
            [crux.index :as idx]
            [crux.kv-store :as ks]
            [crux.db :as db]
            [crux.lru :as lru]
            [taoensso.nippy :as nippy])
  (:import [java.nio ByteBuffer]
           [java.io Closeable]
           [java.util Date]))

(set! *unchecked-math* :warn-on-boxed)

;; Docs

(defn- attribute-value+content-hashes-for-current-key [i ^bytes current-k attr max-v peek-state]
  (let [max-seek-k (idx/encode-attribute+value-prefix-key attr (or max-v idx/empty-byte-array))
        prefix-size (- (alength current-k) idx/id-size)]
    (loop [acc []
           k current-k]
      (if-let [value+content-hash (when (and k
                                             (bu/bytes=? current-k prefix-size k)
                                             (not (neg? (bu/compare-bytes max-seek-k k (alength max-seek-k)))))
                                    (idx/decode-attribute+value+content-hash-key->value+content-hash k))]
        (recur (conj acc value+content-hash)
               (ks/-next i))
        (do (reset! peek-state k)
            (when (seq acc)
              acc))))))

(defrecord DocAttributeValueIndex [i attr max-v peek-state]
  db/Index
  (-seek-values [this k]
    (when-let [k (->> (or k idx/empty-byte-array)
                      (idx/encode-attribute+value-prefix-key attr)
                      (ks/-seek i))]
      (attribute-value+content-hashes-for-current-key i k attr max-v peek-state)))

  db/OrderedIndex
  (-next-values [this]
    (when-let [k (or @peek-state (ks/-next i))]
      (attribute-value+content-hashes-for-current-key i k attr max-v peek-state))))

(defn- new-doc-attribute-value-index [i attr max-v]
  (->DocAttributeValueIndex i attr max-v (atom nil)))

(defn- normalize-value [v]
  (cond-> v
    (not (or (vector? v)
             (set? v))) (vector)))

(defn index-doc [kv content-hash doc]
  (ks/store kv (for [[k v] doc
                     v (normalize-value v)]
                 [(idx/encode-attribute+value+content-hash-key k v content-hash)
                  idx/empty-byte-array])))

(defn delete-doc-from-index [kv content-hash doc]
  (ks/delete kv (for [[k v] doc
                      v (normalize-value v)]
                  (idx/encode-attribute+value+content-hash-key k v content-hash))))

(defrecord DocObjectStore [kv]
  db/ObjectStore
  (get-objects [this ks]
    (with-open [snapshot (ks/new-snapshot kv)
                i (ks/new-iterator snapshot)]
      (->> (for [seek-k (->> (map idx/encode-doc-key ks)
                             (sort bu/bytes-comparator))
                 :let [k (ks/-seek i seek-k)]
                 :when (and k (bu/bytes=? seek-k k))]
             [(idx/decode-doc-key k)
              (nippy/fast-thaw (ks/-value i))])
           (into {}))))

  (put-objects [this kvs]
    (ks/store kv (for [[k v] kvs]
                   [(idx/encode-doc-key k)
                    (nippy/fast-freeze v)])))

  (delete-objects [this ks]
    (ks/delete kv (map idx/encode-doc-key ks)))

  Closeable
  (close [_]))

;; Meta

(defn store-meta [kv k v]
  (ks/store kv [[(idx/encode-meta-key k)
                 (nippy/fast-freeze v)]]))

(defn read-meta [kv k]
  (with-open [snapshot (ks/new-snapshot kv)
              i (ks/new-iterator snapshot)]
    (when-let [k (ks/-seek i (idx/encode-meta-key k))]
      (nippy/fast-thaw (ks/-value i)))))

;; Utils

(defn- all-keys-in-prefix
  ([i prefix]
   (all-keys-in-prefix i prefix false))
  ([i prefix entries?]
   ((fn step [f-cons f-next]
          (lazy-seq
           (let [k (f-cons)]
             (when (and k (bu/bytes=? prefix k))
               (cons (if entries?
                       [k (ks/-value i)]
                       k) (step f-next f-next))))))
    #(ks/-seek i prefix) #(ks/-next i))))

;; Entities

(defn- enrich-entity-map [entity-map content-hash]
  (assoc entity-map :content-hash (some-> content-hash not-empty idx/new-id)))

(defrecord EntityAsOfIndex [i business-time transact-time]
  db/Index
  (db/-seek-values [this k]
    (let [prefix-size (+ Short/BYTES idx/id-size)
          seek-k (idx/encode-entity+bt+tt-prefix-key
                  k
                  business-time
                  transact-time)]
      (loop [k (ks/-seek i seek-k)]
        (when (and k (bu/bytes=? seek-k prefix-size k))
          (let [v (ks/-value i)
                entity-map (-> (idx/decode-entity+bt+tt+tx-id-key k)
                               (enrich-entity-map v))]
            (if (<= (compare (:tt entity-map) transact-time) 0)
              (when-not (bu/bytes=? idx/nil-id-bytes v)
                [entity-map])
              (recur (ks/-next i)))))))))

(defn entities-at [snapshot entities business-time transact-time]
  (with-open [i (ks/new-iterator snapshot)]
    (let [entity-as-of-idx (->EntityAsOfIndex i business-time transact-time)]
      (vec (mapcat #(db/-seek-values entity-as-of-idx %) entities)))))

(defrecord ContentHashEntityIndex [i]
  db/Index
  (db/-seek-values [this k]
    (->> (idx/encode-content-hash-prefix-key k)
         (all-keys-in-prefix i)
         (map idx/decode-content-hash+entity-key->entity))))

(defn- value+content-hashes->value+entities [content-hash-entity-idx entity-as-of-idx value+content-hashes]
  (when-let [[[v]] value+content-hashes]
    (for [content-hash (map second value+content-hashes)
          entity (db/-seek-values content-hash-entity-idx content-hash)
          entity-map (db/-seek-values entity-as-of-idx entity)
          :when (= content-hash (:content-hash entity-map))]
      [v entity-map])))

(defrecord EntityAttributeValueVirtualIndex [doc-idx content-hash-entity-idx entity-as-of-idx]
  db/Index
  (-seek-values [this k]
    (->> (db/-seek-values doc-idx k)
         (value+content-hashes->value+entities content-hash-entity-idx entity-as-of-idx)))

  db/OrderedIndex
  (-next-values [this]
    (->> (db/-next-values doc-idx)
         (value+content-hashes->value+entities content-hash-entity-idx entity-as-of-idx))))

(defn entities-by-attribute-value-at [snapshot attr min-v max-v business-time transact-time]
  (with-open [di (ks/new-iterator snapshot)
              ci (ks/new-iterator snapshot)
              ei (ks/new-iterator snapshot)]
    (let [doc-idx (new-doc-attribute-value-index di attr max-v)
          content-hash-entity-idx (->ContentHashEntityIndex ci)
          entity-as-of-idx (->EntityAsOfIndex ei business-time transact-time)
          entity-attribute-idx (->EntityAttributeValueVirtualIndex doc-idx content-hash-entity-idx entity-as-of-idx)]
      (when-let [k (db/-seek-values entity-attribute-idx min-v)]
        (->> (repeatedly #(db/-next-values entity-attribute-idx))
             (take-while identity)
             (apply concat k)
             (map second)
             (vec))))))

(defn all-entities [snapshot business-time transact-time]
  (with-open [i (ks/new-iterator snapshot)]
    (let [eids (->> (all-keys-in-prefix i (idx/encode-entity+bt+tt-prefix-key))
                    (map (comp :eid idx/decode-entity+bt+tt+tx-id-key))
                    (distinct))]
      (entities-at snapshot eids business-time transact-time))))

(defn entity-history [snapshot entity]
  (with-open [i (ks/new-iterator snapshot)]
    (let [seek-k (idx/encode-entity+bt+tt-prefix-key entity)]
      (vec (for [[k v] (all-keys-in-prefix i seek-k true)]
             (-> (idx/decode-entity+bt+tt+tx-id-key k)
                 (enrich-entity-map v)))))))

;; Join

(defn- new-leapfrog-iterator-state [idx attr value+entities]
  (let [[[v]] value+entities]
    {:attr attr
     :idx idx
     :key (or v idx/nil-id-bytes)
     :entities (mapv second value+entities)}))

(defrecord UnaryJoinVirtualIndex [entity-indexes iterators-state]
  db/Index
  (-seek-values [this k]
    (let [iterators (->> (for [entity-idx entity-indexes
                               :let [attr (get-in entity-idx [:doc-idx :attr])]]
                           (new-leapfrog-iterator-state entity-idx attr (db/-seek-values entity-idx k)))
                         (sort-by :key bu/bytes-comparator)
                         (vec))]
      (reset! iterators-state {:iterators iterators :index 0})
      (db/-next-values this)))

  db/OrderedIndex
  (-next-values [this]
    (when-let [{:keys [iterators ^long index]} @iterators-state]
      (let [{:keys [key attr idx]} (get iterators index)
            max-index (mod (dec index) (count iterators))
            max-k (:key (get iterators max-index))
            match? (bu/bytes=? key max-k)
            next-value+entities (if match?
                                  (do (log/debug :next attr)
                                      (db/-next-values idx))
                                  (do (log/debug :seek attr (bu/bytes->hex max-k))
                                      (db/-seek-values idx (reify idx/ValueToBytes
                                                             (value->bytes [_]
                                                               max-k)))))]
        (reset! iterators-state
                (when next-value+entities
                  {:iterators (assoc iterators index (new-leapfrog-iterator-state idx attr next-value+entities))
                   :index (mod (inc index) (count iterators))}))
        (if match?
          (let [attrs (map :attr iterators)]
            (log/debug :match attrs (bu/bytes->hex max-k))
            [max-k (zipmap attrs (mapv :entities iterators))])
          (recur))))))

(defn- new-unary-join-virtual-index [entity-indexes]
  (->UnaryJoinVirtualIndex entity-indexes (atom nil)))

(defn unary-leapfrog-join [snapshot attrs min-v max-v business-time transact-time]
  (let [attr->di (zipmap attrs (repeatedly #(ks/new-iterator snapshot)))]
    (try
      (with-open [ci (ks/new-iterator snapshot)
                  ei (ks/new-iterator snapshot)]
        (let [content-hash-entity-idx (->ContentHashEntityIndex ci)
              entity-as-of-idx (->EntityAsOfIndex ei business-time transact-time)
              entity-indexes (for [[attr di] attr->di
                                   :let [doc-idx (new-doc-attribute-value-index di attr max-v)]]
                               (->EntityAttributeValueVirtualIndex doc-idx content-hash-entity-idx entity-as-of-idx))
              unary-join-idx (new-unary-join-virtual-index entity-indexes)]
          (when-let [result (db/-seek-values unary-join-idx min-v)]
            (->> (repeatedly #(db/-next-values unary-join-idx))
                 (take-while identity)
                 (cons result)
                 (vec)))))
      (finally
        (doseq [i (vals attr->di)]
          (.close ^Closeable i))))))

(defn- constrain-triejoin-result [result shared-attrs]
  (->> shared-attrs
       (reduce
        (fn [result attrs]
          (->> (map (comp set result) attrs)
               (apply (comp vec set/intersection))
               (repeat)
               (zipmap attrs)
               (merge result)))
        (into {} result))
       (vec)))

(defn- flatten-triejoin-result-stack [result-stack]
  (loop [[[v matches :as level] & levels] result-stack
         v-acc []
         acc []]
    (if level
      (recur levels
             (conj v-acc v)
             (vec (concat acc matches)))
      [v-acc acc])))

(defrecord TriejoinVirtualIndex [unary-join-indexes shared-attrs trie-state]
  db/Index
  (-seek-values [this k]
    (reset! trie-state {:needs-seek? true
                        :min-vs (vec k)
                        :result-stack []})
    (db/-next-values this))

  db/OrderedIndex
  (-next-values [this]
    (let [{:keys [needs-seek? min-vs result-stack]
           :as state} @trie-state
          depth (count result-stack)
          max-depth (dec (count unary-join-indexes))
          idx (get unary-join-indexes depth)
          values (if needs-seek?
                   (db/-seek-values idx (get min-vs depth))
                   (db/-next-values idx))
          result-stack (conj result-stack values)]
      (swap! trie-state assoc :needs-seek? false)
      (cond
        (and values (= depth max-depth))
        (do (log/debug :leaf-match result-stack)
            (let [[max-ks result] (flatten-triejoin-result-stack result-stack)
                  result (constrain-triejoin-result result shared-attrs)]
              (log/debug :leaf-match-constrained (mapv bu/bytes->hex max-ks) result)
              [max-ks result]))

        values
        (do (log/debug :open-level depth)
            (swap! trie-state #(-> %
                                   (assoc :needs-seek? true)
                                   (update :result-stack conj values)))
            (recur))

        (and (nil? values) (pos? depth))
        (do (log/debug :close-level depth)
            (swap! trie-state update :result-stack pop)
            (recur))))))

(defn- new-triejoin-virtual-index [unary-join-indexes shared-attrs]
  (->TriejoinVirtualIndex unary-join-indexes shared-attrs (atom nil)))

(defn leapfrog-triejoin [snapshot unary-attrs shared-attrs business-time transact-time]
  (let [attr->di+max-v (->> (for [attrs unary-attrs
                                  attr (butlast attrs)
                                  :let [[min-v max-v] (last attrs)]]
                              [attr [(ks/new-iterator snapshot) max-v]])
                            (into {}))]
     (try
       (with-open [ci (ks/new-iterator snapshot)
                   ei (ks/new-iterator snapshot)]
         (let [content-hash-entity-idx (->ContentHashEntityIndex ci)
               entity-as-of-idx (->EntityAsOfIndex ei business-time transact-time)
               attr->entity-indexes (->> (for [[attr [di max-v]] attr->di+max-v
                                               :let [doc-idx (new-doc-attribute-value-index di attr max-v)]]
                                           [attr (->EntityAttributeValueVirtualIndex doc-idx content-hash-entity-idx entity-as-of-idx)])
                                         (into {}))
               triejoin-idx (-> (for [attrs unary-attrs]
                                  (->> (mapv attr->entity-indexes (butlast attrs))
                                       (new-unary-join-virtual-index)))
                                (vec)
                                (new-triejoin-virtual-index shared-attrs))
               min-vs (vec (for [attrs unary-attrs
                                 :let [[min-v max-v] (last attrs)]]
                             min-v))]
           (when-let [result (db/-seek-values triejoin-idx min-vs)]
             (->> (repeatedly #(db/-next-values triejoin-idx))
                  (take-while identity)
                  (cons result)
                  (vec)))))
       (finally
         (doseq [[i] (vals attr->di+max-v)]
           (.close ^Closeable i))))))

;; Caching

(defrecord CachedObjectStore [cache object-store]
  db/ObjectStore
  (get-objects [this ks]
    (->> (for [k ks]
           [k (lru/compute-if-absent
               cache
               k
               #(get (db/get-objects object-store [%]) %))])
         (into {})))

  (put-objects [this kvs]
    (db/put-objects object-store kvs))

  (delete-objects [this ks]
    (doseq [k ks]
      (lru/evict cache k))
    (db/delete-objects object-store ks))

  Closeable
  (close [_]))

;; Query

(defrecord DocEntity [object-store eid content-hash bt]
  db/Entity
  (attr-val [this ident]
    (get (db/->map this) ident))

  (->id [this]
    ;; TODO: we want to get rid of the need for :crux.db/id
    (or (db/attr-val this :crux.db/id) eid))

  (->map [this]
    (get (db/get-objects object-store [content-hash]) content-hash))

  (->business-time [this]
    bt)

  (eq? [this that]
    (= eid (:eid that))))

(defrecord DocCachedIterator [iterators-state i]
  ks/KvIterator
  (-seek [_ k]
    (ks/-seek i k))

  (-next [_]
    (ks/-next i))

  (-value [_]
    (ks/-value i))

  Closeable
  (close [_]
    (swap! iterators-state conj i)))

(defrecord DocSnapshot [^Closeable snapshot iterators-state]
  ks/KvSnapshot
  (new-iterator [_]
    (let [is @iterators-state]
      (if-let [i (first is)]
        (if (compare-and-set! iterators-state is (disj is i))
          (->DocCachedIterator iterators-state i)
          (recur))
        (->> (ks/new-iterator snapshot)
             (->DocCachedIterator iterators-state)))))

  Closeable
  (close [_]
    (doseq [^Closeable i @iterators-state]
      (.close i))
    (.close snapshot)))

(defrecord DocDatasource [kv object-store business-time transact-time]
  db/Datasource
  (new-query-context [this]
    (->DocSnapshot (ks/new-snapshot kv) (atom #{})))

  (entities [this query-context]
    (for [entity-map (all-entities query-context business-time transact-time)]
      (map->DocEntity (assoc entity-map :object-store object-store))))

  (entities-for-attribute-value [this query-context ident min-v max-v]
    (for [entity-map (entities-by-attribute-value-at query-context ident min-v max-v business-time transact-time)]
      (map->DocEntity (assoc entity-map :object-store object-store))))

  (entity-join [this query-context attrs min-v max-v]
    (for [entity-map (->> (unary-leapfrog-join query-context attrs min-v max-v business-time transact-time)
                          (map second)
                          (mapcat vals)
                          (apply concat)
                          (distinct))]
      (map->DocEntity (assoc entity-map :object-store object-store))))

  (entity [this query-context eid]
    (when-let [entity-map (first (entities-at query-context [eid] business-time transact-time))]
      (map->DocEntity (assoc entity-map :object-store object-store))))

  (entity-history [this query-context eid]
    (for [entity-map (entity-history query-context eid)]
      (map->DocEntity (assoc entity-map :object-store object-store)))))

(def ^:const default-await-tx-timeout 10000)

(defn- await-tx-time [kv transact-time ^long timeout]
  (let [timeout-at (+ timeout (System/currentTimeMillis))]
    (while (pos? (compare transact-time (read-meta kv :crux.tx-log/tx-time)))
      (Thread/sleep 100)
      (when (>= (System/currentTimeMillis) timeout-at)
        (throw (IllegalStateException. (str "Timed out waiting for: " transact-time)))))))

(def ^:const default-doc-cache-size 10240)

(defn- named-cache [state cache-name cache-size]
  (get (swap! state
              update
              cache-name
              (fn [cache]
                (or cache (lru/new-cache cache-size))))
       cache-name))

(defn- new-cached-object-store [kv cache-size]
  (->CachedObjectStore (named-cache (:state kv)::doc-cache cache-size)
                       (->DocObjectStore kv)))

(defn db
  ([kv]
   (db kv (Date.)))
  ([kv business-time]
   (->DocDatasource kv
                    (new-cached-object-store kv default-doc-cache-size)
                    business-time
                    (Date.)))
  ([kv business-time transact-time]
   (await-tx-time kv transact-time default-await-tx-timeout)
   (->DocDatasource kv
                    (new-cached-object-store kv default-doc-cache-size)
                    business-time
                    transact-time)))
