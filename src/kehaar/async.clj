(ns kehaar.async
  (:require [clojure.core.async :as async]))

(defn bounded>!!
  "Like async/>!, but with a timeout."
  [channel message timeout]
  (async/alt!!
    [[channel message]] true
    (async/timeout timeout) false))

(defn bounded<!! [channel timeout]
  (async/alt!!
    channel ([v] v)
    (async/timeout timeout) ([] (throw (ex-info "<!! timeout out" {})))))
