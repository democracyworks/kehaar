(ns democracyworks.kehaar
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [langohr.basic :as lb]))

(defn read-payload [^bytes payload]
  (-> payload
      (String. "UTF-8")
      edn/read-string))

(defn pass-through
  "Returns a RabbitMQ message handler function which forwards all
  messages to `channel`. Assumes that all payloads are UTF-8 edn
  strings."
  [channel]
  (fn [ch meta ^bytes payload]
    (async/go
      (let [message (read-payload payload)]
        (async/>! channel [ch meta message])))))

(defn simple-pass-through
  "Like `pass-through`, but only passes the message to
  `channel`. Useful if you don't care about the `ch` or `meta`
  arguments from RabbitMQ for a particular use-case."
  [channel]
  (let [middleman (async/chan)]
    (async/pipeline 1 channel (map #(nth % 2)) middleman)
    (pass-through middleman)))

(defn simple-responder
  "Returns a RabbitMQ message handler function which calls f for each
  incoming message and replies on the reply-to channel with the
  response."
  ([f] (simple-responder f ""))
  ([f exchange]
   (fn [ch {:keys [reply-to correlation-id]} ^bytes payload]
     (let [message (read-payload payload)
           response (f message)]
       (lb/publish ch exchange reply-to (pr-str response)
                   {:correlation-id correlation-id})))))

(defn ch->response-fn
  "Returns a fn that takes a message, creates a core.async channel for
  the response for that message, and puts [response-channel, message]
  on the channel given. Returns the response-channel."
  [channel]
  (fn [message]
    (let [response-channel (async/chan)]
      (async/go
        (async/>! channel [response-channel message]))
      response-channel)))
