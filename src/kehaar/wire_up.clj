(ns kehaar.wire-up
  (:require
   [langohr.queue]
   [langohr.channel]
   [langohr.exchange]
   [kehaar.core]
   [clojure.tools.logging :as log]
   [clojure.core.async :as async]))

(defn incoming-events-channel
  "Wire up a channel that will receive incoming events that match
  `routing-key`.

  Returns a langohr channel. Please close it on exit."
  [connection queue-name options topic-name routing-key channel timeout]
  (let [ch (langohr.channel/open connection)
        queue (:queue (langohr.queue/declare ch queue-name options))
        message-channel (async/chan 1 (map :message))]
    (async/pipe message-channel channel true)
    (langohr.queue/bind ch queue topic-name {:routing-key routing-key})
    (kehaar.core/rabbit=>async ch queue message-channel options timeout)
    ch))

(defn outgoing-events-channel
  "Wire up a queue listening to a channel for events.

  Returns a langohr channel. Please close it on exit."
  [connection topic-name routing-key channel]
  (let [ch (langohr.channel/open connection)
        message-channel (async/chan 1000 (map (fn [x] {:message x})))]
    (async/pipe channel message-channel true)
    (kehaar.core/async=>rabbit message-channel ch topic-name routing-key)
    ch))

(defn declare-events-exchange
  "Declare an events exchange.

  Returns a langohr channel. Please close it on exit."
  [connection name type options]
  (let [ch (langohr.channel/open connection)]
    (langohr.exchange/declare ch name type options)
    ch))

(defn start-event-handler!
  "Start a new thread listening for messages on `channel` and passing
  them to `handler`. Will loop over all messages, logging errors. When
  `channel` is closed, stop looping."
  [channel handler]
  (kehaar.core/thread-handler channel handler))

(defn start-responder!
  "Start a new thread that listens on in-channel and responds on
  out-channel."
  [in-channel out-channel f]
  (kehaar.core/thread-handler
   in-channel
   (kehaar.core/responder-fn out-channel f)))

(defn start-streaming-responder!
  "Start a new thread that listens on in-channel and responds on
  out-channel. threshold is the number of elements beyond which they
  should be placed on a bespoke RabbitMQ for the consumer."
  [connection in-channel out-channel f threshold]
  (kehaar.core/thread-handler
   in-channel
   (kehaar.core/streaming-responder-fn connection out-channel f threshold)))

(defn incoming-service
  "Wire up an incoming channel and an outgoing channel. Later, you
  should call `start-responder!` with the same channels and a handler
  function.

  Returns a langohr channel. Please close it on exit."
  [connection queue-name options in-channel out-channel]
  (let [ch (langohr.channel/open connection)]
    (langohr.queue/declare ch queue-name options)
    (kehaar.core/rabbit=>async ch queue-name in-channel)
    (kehaar.core/async=>rabbit-with-reply-to out-channel ch)
    ch))

(defn external-service
  "Wires up a core.async channel to a RabbitMQ queue that provides
  responses. Use `async->fn` to create a function that puts to
  that channel."
  ([connection queue-name channel]
   (external-service connection ""
                     queue-name {:exclusive false
                                 :durable true
                                 :auto-delete false}
                     1000 channel))
  ([connection exchange queue-name queue-options timeout channel]
   (let [ch (langohr.channel/open connection)]
     (langohr.queue/declare ch queue-name queue-options)
     (let [response-queue (langohr.queue/declare-server-named
                           ch
                           {:exclusive true
                            :auto-delete true})
           pending-calls (atom {})
           <response-channel (async/chan)
           >request-channel (async/chan 1000)]

       ;; start listening for responses
       (kehaar.core/rabbit=>async ch response-queue <response-channel
                                  {:exclusive true} 1000)
       (kehaar.core/go-handler
        [{:keys [message metadata]} <response-channel]
        (let [correlation-id (:correlation-id metadata)]
          (when-let [return-channel (get @pending-calls correlation-id)]
            (async/>! return-channel message)
            (swap! pending-calls dissoc correlation-id))))

       ;; bookkeeping for sending the requests
       (kehaar.core/async=>rabbit >request-channel ch exchange queue-name)
       (kehaar.core/go-handler
        [[return-channel message] channel]
        (let [correlation-id (str (java.util.UUID/randomUUID))]
          (swap! pending-calls assoc correlation-id return-channel)
          (async/>! >request-channel {:message message
                                      :metadata {:correlation-id correlation-id
                                                 :reply-to response-queue
                                                 :mandatory true}})
          (async/go
            (async/<! (async/timeout timeout))
            (when-let [chan (get @pending-calls correlation-id)]
              (async/close! chan)
              (swap! pending-calls dissoc correlation-id))))))
     ch)))

(defn external-service-fire-and-forget
  "Wires up a core.async channel to a RabbitMQ queue. Just put a
  message on the channel, it's that easy!"
  ([connection queue-name channel]
   (external-service connection ""
                     queue-name {:exclusive false
                                 :durable true
                                 :auto-delete false}
                     channel))
  ([connection exchange queue-name queue-options channel]
   (let [ch (langohr.channel/open connection)]
     (langohr.queue/declare ch queue-name queue-options)

     (kehaar.core/async=>rabbit channel ch exchange queue-name)
     ch)))

(defn streaming-external-service
  "Wires up a core.async channel to a RabbitMQ queue that provides
  responses. Use `async->fn` to create a function that puts to
  that channel."
  ([connection queue-name channel]
   (streaming-external-service connection ""
                               queue-name {:exclusive false
                                           :durable true
                                           :auto-delete false}
                               1000 channel))
  ([connection exchange queue-name queue-options timeout channel]
   (let [ch (langohr.channel/open connection)]
     (langohr.queue/declare ch queue-name queue-options)
     (let [response-queue (langohr.queue/declare-server-named
                           ch
                           {:exclusive true
                            :auto-delete true})
           pending-calls (atom {})
           <response-channel (async/chan)
           >request-channel (async/chan 1000)]

       ;; start listening for responses
       (kehaar.core/rabbit=>async ch response-queue <response-channel
                                  {:exclusive true} 1000)
       (kehaar.core/go-handler
        [{:keys [message metadata]} <response-channel]
        (let [correlation-id (:correlation-id metadata)]
          (cond
            (= :kehaar.core/stop message)
            (when-let [return-channel (get-in @pending-calls [correlation-id :return-channel])]
              (async/close! return-channel)
              (swap! pending-calls dissoc correlation-id))

            (and (map? message)
                 (:kehaar.core/inline message))
            nil                         ; do nothing

            (and (map? message)
                 (:kehaar.core/response-queue message))
            (if-let [return-channel (get-in @pending-calls [correlation-id :return-channel])]
              (let [message-channel (async/chan 1 (map :message))
                    response-queue (:kehaar.core/response-queue message)]
                (kehaar.core/rabbit=>async
                 ch
                 response-queue
                 message-channel
                 {:exclusive true}
                 100
                 true)
                (loop []
                  (let [msg (async/<! message-channel)]
                    (when (get-in @pending-calls [correlation-id :timeout])
                      (swap! pending-calls update correlation-id dissoc :timeout))
                    (if (nil? msg)
                      (async/close! return-channel)
                      (if (async/>! return-channel msg)
                        (recur)
                        (async/close! message-channel))))))
              (do
                (log/info (format "Deleting queue %s" (:kehaar.core/response-queue message)))
                (langohr.queue/delete ch (:kehaar.core/response-queue message))))

            :else
            (when-let [return-channel (get-in @pending-calls [correlation-id :return-channel])]
              (when (get-in @pending-calls [correlation-id :timeout])
                (swap! pending-calls update correlation-id dissoc :timeout))
              (async/>! return-channel message)))))

       ;; bookkeeping for sending the requests
       (kehaar.core/async=>rabbit >request-channel ch exchange queue-name)
       (kehaar.core/go-handler
        [[return-channel message] channel]
        (let [correlation-id (str (java.util.UUID/randomUUID))
              timeout-ch (async/timeout timeout)]
          (swap! pending-calls assoc correlation-id {:return-channel return-channel
                                                     :timeout timeout-ch})
          (async/>! >request-channel {:message message
                                      :metadata {:correlation-id correlation-id
                                                 :reply-to response-queue
                                                 :mandatory true}})

          (async/go
            (async/<! timeout-ch)
            (when (get-in @pending-calls [correlation-id :timeout])
              (log/info "Streaming request timed out")
              (async/close! (get-in @pending-calls [correlation-id :return-channel]))
              (swap! pending-calls dissoc correlation-id))))))
     ch)))

(defn async->fn
  "Returns a fn that takes a message, creates a core.async channel for
  the response for that message, and puts [response-channel, message]
  on the channel given. Returns the response-channel."
  [channel]
  (fn [message]
    (let [response-channel (async/chan 1)]
      (async/>!! channel [response-channel message])
      response-channel)))

(defn async->fire-and-forget-fn
  "Returns a fn that takes a message and puts message and metadata on
  the channel. Returns the value of async/>!! which returns true if it
  was successfull putting a message on the channel."
  ([channel]
   (async->fire-and-forget-fn channel {}))
  ([channel metadata]
   (fn [message]
     (async/>!! channel {:metadata metadata
                         :message message}))))
