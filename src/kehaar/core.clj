(ns kehaar.core
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [langohr.basic :as lb]
            [langohr.consumers :as lc]
            [langohr.queue :as lq]
            [clojure.tools.logging :as log]))

(defn read-payload [^bytes payload]
  (-> payload
      (String. "UTF-8")
      edn/read-string))

(defn rabbit->async-handler-fn
  "Returns a RabbitMQ message handler function which forwards all
  message payloads to `channel`. Assumes that all payloads are UTF-8
  edn strings. Returned fn returns the message delivery tag."
  [channel]
  (fn [ch {:keys [delivery-tag]} ^bytes payload]
    (let [message (read-payload payload)]
      (log/debug "Kehaar: Consuming message" (str "(delivery-tag " delivery-tag "): ") (pr-str message))
      (async/>!! channel message)
      (log/debug "Kehaar: Successfully forwarded message" (str "(delivery-tag " delivery-tag ")") "to core.async channel")
      delivery-tag)))

(defn rabbit->async
  "Subscribes to the RabbitMQ queue, taking each payload, decoding as
  edn, and putting the result onto the async channel."
  ([rabbit-channel queue channel]
   (rabbit->async rabbit-channel queue channel {}))
  ([rabbit-channel queue channel options]
   (lc/subscribe rabbit-channel
                 queue
                 (comp (partial lb/ack rabbit-channel)
                       (rabbit->async-handler-fn channel))
                 (merge options {:auto-ack false}))))

(defn async->rabbit
  "Forward all messages on channel to the RabbitMQ queue."
  ([channel rabbit-channel queue]
   (async->rabbit channel rabbit-channel "" queue))
  ([channel rabbit-channel exchange queue]
   (async/go-loop []
     (let [message (async/<! channel)]
       (if (nil? message)
         (log/warn "Kehaar: core.async channel for" queue "is closed")
         (do (lb/publish rabbit-channel exchange queue (pr-str message))
             (recur)))))))

(defn fn->handler-fn
  "Returns a RabbitMQ message handler function which calls f for each
  incoming message and replies on the reply-to queue with the
  response."
  ([f] (fn->handler-fn f ""))
  ([f exchange]
   (fn [ch {:keys [reply-to correlation-id]} ^bytes payload]
     (let [message (read-payload payload)
           response (f message)]
       (lb/publish ch exchange reply-to (pr-str response)
                   {:correlation-id correlation-id})))))

(defn responder
  "Given a RabbitMQ queue and a function, subscribes to that queue,
  calling the function on each edn-decoded message, and replies to the
  reply-to queue with the result."
  ([rabbit-channel queue f]
   (responder rabbit-channel queue f {:auto-ack true}))
  ([rabbit-channel queue f opts]
   (let [handler-fn (fn->handler-fn f)]
     (lc/subscribe rabbit-channel
                   queue
                   handler-fn
                   opts))))

(defn ch->response-fn
  "Returns a fn that takes a message, creates a promise for the
  response for that message, and puts [response-promise, message] on
  the channel given. Returns the response-promise."
  [channel]
  (fn [message]
    (let [response-promise (promise)]
      (async/go
        (async/>! channel [response-promise message]))
      response-promise)))

(defn wire-up-service
  "Wires up a core.async channel (managed through ch->response-fn) to
  a RabbitMQ queue that provides responses."
  ([rabbit-channel queue channel]
   (wire-up-service rabbit-channel ""
                    queue {:exclusive false :auto-delete true}
                    (* 5 60 1000) channel))
  ([rabbit-channel exchange queue queue-options timeout channel]
   (let [response-queue (lq/declare-server-named rabbit-channel {:exclusive true :auto-delete true})
         pending-calls (atom {})]
     (lc/subscribe rabbit-channel
                   response-queue
                   (fn [ch {:keys [correlation-id]} ^bytes payload]
                     (when-let [response-promise (@pending-calls correlation-id)]
                       (deliver response-promise (read-payload payload))
                       (swap! pending-calls dissoc correlation-id)))
                   {:auto-ack true})
     (async/go-loop []
       (let [ch-message (async/<! channel)]
         (if (nil? ch-message)
           (log/warn "Kehaar: core.async channel for" queue "is closed")
           (let [[response-promise message] ch-message
                 correlation-id (str (java.util.UUID/randomUUID))]
             (swap! pending-calls assoc correlation-id response-promise)
             (lb/publish rabbit-channel
                         exchange
                         queue
                         (pr-str message)
                         {:reply-to response-queue
                          :correlation-id correlation-id})
             (async/go
               (async/<! (async/timeout timeout))
               (swap! pending-calls dissoc correlation-id))
             (recur))))))))

(defn fn->stream-handler-fn
  "Returns a RabbitMQ message handler function which calls f for each
  incoming message. f is a function that takes an async channel and a
  message, and puts one or more responses on the channel, ending with
  a single :stop value. Each value taken from the channel is published
  to the reply-to queue, and when it's done, a :stream-stop value is
  published."
  ([f] (fn->stream-handler-fn f ""))
  ([f exchange]
   (fn [ch {:keys [reply-to correlation-id]} ^bytes payload]
     (let [message (read-payload payload)
           responses-chan (async/chan)]
       (async/go
         (f responses-chan message)
         (loop [response (async/<! responses-chan)]
           (if (or (nil? response) (= :stop response))
             (do (async/close! responses-chan)
                 (lb/publish ch exchange reply-to (pr-str :stream-stop)
                             {:correlation-id correlation-id}))
             (do (lb/publish ch exchange reply-to (pr-str response)
                             {:correlation-id correlation-id})
                 (recur (async/<! responses-chan))))))))))

(defn stream-responder
  "Given a RabbitMQ queue and a stream function, subscribes to that queue,
  calling the function on each edn-decoded message with a response async
  channel, and takes values from the channel and publishes them to the
  reply-to queue until a :stop message is received or the channel closes
  or nil is returned. Utilizes fn->stream-handler-fn."
  ([rabbit-channel queue f]
   (stream-responder rabbit-channel queue f {:auto-ack true}))
  ([rabbit-channel queue f opts]
   (let [handler-fn (fn->stream-handler-fn f)]
     (lc/subscribe rabbit-channel
                   queue
                   handler-fn
                   opts))))

(defn ch->stream-response-fn
  "Returns a fn that takes a message, response-fn and a stop-fn,
  such that when responses for that message are streamed back,
  they are handed to the response-fn for processing. When the stream
  is done, the stop-fn will be called, if provided. Utilized with
  wire-up-stream-service."
  [channel]
  (fn [message response-fn stop-fn]
    (async/go
      (async/>! channel [response-fn stop-fn message]))))

(defn wire-up-stream-service
  "Wires up a core.async channel (managed through ch->stream-response-fn) to
  a RabbitMQ queue that provides a stream of responses."
  ([rabbit-channel queue channel]
   (wire-up-stream-service rabbit-channel ""
                    queue {:exclusive false :auto-delete true}
                    (* 5 60 1000) channel))
  ([rabbit-channel exchange queue queue-options timeout channel]
   (let [response-queue (lq/declare-server-named rabbit-channel {:exclusive true :auto-delete true})
         pending-calls (atom {})]
     (lc/subscribe rabbit-channel
                   response-queue
                   (fn [ch {:keys [correlation-id]} ^bytes payload]
                     (when-let [{:keys [response-fn stop-fn]} (@pending-calls correlation-id)]
                       (let [response (read-payload payload)]
                         (if (= :stream-stop response)
                           (do
                             (swap! pending-calls dissoc correlation-id)
                             (when stop-fn (stop-fn)))
                           (response-fn response)))))
                   {:auto-ack true})
     (async/go-loop []
       (let [ch-message (async/<! channel)]
         (if (nil? ch-message)
           (log/warn "Kehaar: core.async channel for" queue "is closed")
           (let [[response-fn stop-fn message] ch-message
                 correlation-id (str (java.util.UUID/randomUUID))]
             (swap! pending-calls assoc correlation-id {:response-fn response-fn
                                                        :stop-fn stop-fn})
             (lb/publish rabbit-channel
                         exchange
                         queue
                         (pr-str message)
                         {:reply-to response-queue
                          :correlation-id correlation-id})
             #_(async/go
               (async/<! (async/timeout timeout))
               (swap! pending-calls dissoc correlation-id))
             (recur))))))))
