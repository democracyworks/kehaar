(ns democracyworks.kehaar
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [langohr.basic :as lb]
            [langohr.consumers :as lc]
            [langohr.queue :as lq]))

(defn read-payload [^bytes payload]
  (-> payload
      (String. "UTF-8")
      edn/read-string))

(defn rabbit->async
  "Returns a RabbitMQ message handler function which forwards all
  message payloads to `channel`. Assumes that all payloads are UTF-8
  edn strings."
  [channel]
  (fn [ch meta ^bytes payload]
    (async/go
      (let [message (read-payload payload)]
        (async/>! channel message)))))

(defn async->rabbit
  "Forward all messages on channel to the RabbitMQ queue."
  ([channel rabbit-channel queue]
   (async->rabbit channel rabbit-channel "" queue {:exclusive false :auto-delete true}))
  ([channel rabbit-channel exchange queue queue-options]
   (lq/declare rabbit-channel
               queue
               queue-options)
   (async/go-loop []
     (let [message (async/<! channel)]
       (lb/publish rabbit-channel exchange queue (pr-str message))
       (recur)))))

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

(defn wire-up-service
  "Wires up a core.async channel (managed through ch->response-fn) to
  a RabbitMQ queue that provides responses."
  ([rabbit-channel queue channel]
   (wire-up-service rabbit-channel ""
                    queue {:exclusive false :auto-delete true}
                    1000 channel))
  ([rabbit-channel exchange queue queue-options timeout channel]
   (let [response-queue (str queue "." (java.util.UUID/randomUUID))
         pending-calls (atom {})]
     (lq/declare rabbit-channel
                 queue
                 queue-options)
     (lq/declare rabbit-channel
                 response-queue
                 {:exclusive true :auto-delete true})
     (lc/subscribe rabbit-channel
                   response-queue
                   (fn [ch {:keys [correlation-id]} ^bytes payload]
                     (when-let [response-channel (@pending-calls correlation-id)]
                       (async/go
                         (async/>! response-channel (read-payload payload)))
                       (swap! pending-calls dissoc correlation-id)))
                   {:auto-ack true})
     (async/go-loop []
       (let [[response-channel message] (async/<! channel)
             correlation-id (str (java.util.UUID/randomUUID))]
         (swap! pending-calls assoc correlation-id response-channel)
         (lb/publish rabbit-channel
                     exchange
                     queue
                     (pr-str message)
                     {:reply-to response-queue
                      :correlation-id correlation-id})
         (async/go
           (async/<! (async/timeout timeout))
           (swap! pending-calls dissoc correlation-id))
         (recur))))))
