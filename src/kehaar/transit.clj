(ns kehaar.transit
  (:refer-clojure :exclude [read])
  (:require [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(def custom-readers (atom {}))
(def custom-writers (atom {}))

(def read-handlers (atom (transit/read-handler-map {})))
(def write-handlers (atom (transit/write-handler-map {})))

(add-watch custom-readers ::add-readers
  (fn [_ _ _ new-state]
    (reset! read-handlers
            (transit/read-handler-map new-state))))

(add-watch custom-writers ::add-writers
  (fn [_ _ _ new-state]
    (reset! write-handlers
            (transit/write-handler-map new-state))))

(defn add-readers! [readers]
  (swap! custom-readers merge readers))

(defn add-writers! [writers]
  (swap! custom-writers merge writers))

(defn read
  "Unsafely read a byte array as transit"
  [^bytes payload]
  (let [in (ByteArrayInputStream. payload)
        reader (transit/reader in :json {:handlers @read-handlers})]
    (transit/read reader)))

(defn to-byte-array
  [x]
  (let [out (ByteArrayOutputStream. 4096)
        writer (transit/writer out :json {:handlers @write-handlers})]
    (transit/write writer x)
    (.toByteArray out)))
