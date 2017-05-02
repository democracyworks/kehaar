(ns kehaar.response-queues
  (:import [org.apache.commons.collections4.bidimap TreeBidiMap]
           [clojure.lang Associative ILookup IMapEntry]))

(defprotocol IReverseLookup
  (get-key [this val]))

(deftype ResponseQueueMap [^TreeBidiMap tree-bidi-map]
  Associative
  (containsKey [_ k]
    (.containsKey tree-bidi-map k))

  (entryAt [_ k]
    (if (.containsKey tree-bidi-map k)
      (reify IMapEntry
        (key [_] k)
        (val [_] (.get tree-bidi-map k)))))

  (assoc [this k v]
    (.put tree-bidi-map k v)
    this)

  ILookup
  (valAt [_ k]
    (.get tree-bidi-map k))

  (valAt [_ k not-found]
    (or (.get tree-bidi-map k)
        not-found))

  IReverseLookup
  (get-key [_ v]
    (.getKey tree-bidi-map v)))

(defn response-queue-map []
  (->ResponseQueueMap (TreeBidiMap.)))

(defonce response-queues (atom (response-queue-map)))

(defn set-response-queue! [queue response-queue]
  (swap! response-queues assoc queue response-queue))

(defn get-queue [response-queue]
  (get-key @response-queues response-queue))

(defn get-response-queue [queue]
  (get @response-queues queue))
