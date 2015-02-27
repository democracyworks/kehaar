(ns democracyworks.kehaar
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]))

(defn pass-through
  "Returns a RabbitMQ message handler function which forwards all
  messages to `channel`. Assumes that all payloads are UTF-8 edn
  strings."
  [channel]
  (fn [ch meta ^bytes payload]
    (async/go
      (let [message (-> payload
                        (String. "UTF-8")
                        edn/read-string)]
        (async/>! channel [ch meta message])))))

(defn simple-pass-through
  "Like `pass-through`, but only passes the message to
  `channel`. Useful if you don't care about the `ch` or `meta`
  arguments from RabbitMQ for a particular use-case."
  [channel]
  (let [middleman (async/chan)]
    (async/pipeline 1 channel (map #(nth % 2)) middleman)
    (pass-through middleman)))
