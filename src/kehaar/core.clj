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
  (fn [ch meta ^bytes payload]
    (let [message (read-payload payload)]
      (log/info "Kehaar: Consuming message:" (pr-str message))
      (async/>!! channel message)
      (log/info "Kehaar: Successfully forwarded last message to core.async channel")
      (:delivery-tag meta))))

(defn rabbit->async
  "Subscribes to the RabbitMQ queue, taking each payload, decoding as
  edn, and putting the result onto the async channel."
  ([rabbit-channel queue channel]
   (rabbit->async rabbit-channel queue channel {:auto-ack false}))
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
         (log/warn "Kehaar: Received nil from core.async channel for" queue)
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
           (log/warn "Kehaar: Received nil from core.async channel for" queue)
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
