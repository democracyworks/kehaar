(ns kehaar.configured
  (:require [kehaar.wire-up :as wire-up]
            [kehaar.jobs :as jobs]
            [kehaar.core]
            [kehaar.response-queues :as rq]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [langohr.core :as rmq]
            [langohr.basic :as lb]
            [langohr.channel :as lchan]
            [langohr.consumers :as lc]
            [langohr.queue :as lq]))

(def ^:const default-exchange "")
(def ^:const default-exchange-options {:auto-delete false :durable true})
(def ^:const default-prefetch-limit 1)
(def ^:const default-queue-options {:auto-delete false :durable true :exclusive false})
(def ^:const default-threshold 10)
(def ^:const default-timeout 1000)

(defn require-ns [sym]
  (let [ns' (symbol (namespace sym))]
    (when-not (find-ns ns')
      (require ns'))))

(defn realize-symbol-or-self
  "Given a symbol, gets what it names. Otherwise, returns the
  argument.

  ((realize-symbol-or-self 'clojure.core/inc) 4) ;;=> 5
  ((realize-symbol-or-self inc) 5)               ;;=> 6"
  [x]
  (if (symbol? x)
    (try
      (require-ns x)
      (-> x
          find-var
          var-get)
      (catch Exception e
        (throw
         (ex-info (str "ERROR while trying realize symbol" (pr-str x))
                  {:symbol x, :cause e}))))
    x))

(defn init-exchange!
  "Initializes an exchange.

  Arguments:
  * `connection`: A rabbitmq connection
  * `exchange-options`: A map defining the exchange

  Valid keys for the `exchange-options` are:
  * `:exchange`: The name of the exchange (required)
  * `:type`: The type of the exchange (optional, default `\"topic\"`)
  * `:options`: The options map passed to `langohr.exchange/declare`
  (optional, default {:auto-delete false :durable true})

  Returns a map with one key, `:rabbit-channel`, the value of which is
  the channel created as a side-effect of declaring the exchange. The
  map may be passed to `shutdown-part!` to clean it up.

  Example:

  ```
  (init-exchange! connection {:exchange \"events\"})
  ```

  That will create a topic exchange named \"events\"."
  [connection
   {:keys [exchange type options]
    :or {type "topic"
         options default-exchange-options}}]
  (let [rabbit-ch (wire-up/declare-events-exchange
                   connection
                   exchange
                   type
                   options)]

    {:rabbit-channel rabbit-ch}))

(defn init-incoming-service!
  "Initializes a service that accepts messages on a queue which may
  reply with results.

  Arguments:
  * `connection`: A rabbitmq connection
  * `incoming-service-options`: A map defining the service

  Valid keys for the `incoming-service-options` are:
  * `:f`: A fully qualified symbol for the function that will be
  called for every message received on the queue. It must be a
  function of one argument, which will be a serializable Clojure
  value. For `:streaming` responses, it must return a sequence with
  the values to stream, or a core.async channel to stream values
  from. When returning a core.async channel, you must close the
  channel when there are no more values. For `:streaming` or `true`
  responses, values must be serializable Clojure values. (required)
  * `:queue`: The name of the queue to listen for requests on. (required)
  * `:queue-options`: The options map passed to `langohr.queue/declare`
  for the queue. (optional, default {:auto-delete false :durable true
  :exclusive false})
  * `:response`: How to respond. Key should be `nil`, `:streaming` or
  `true`. `nil` means don't return the result of the function to the
  caller. `:streaming` means the function will return a sequence or a
  core.async channel, the values from which will be returned to the
  caller one by one. `true` means return the result of the function in
  one message back to the caller. (optional, default `nil`)
  * `:exchange`: The name of the exchange. (optional, default `\"\"`)
  * `:prefetch-limit`: The number of messages to prefetch from the
  queue. (optional, default 1).
  * `:threads`: The number of threads to run, each listening for and
  handling messages from the queue. (optional, default 10)
  * `:threshold`: For `:streaming` responses, the number of values at
  which to switch from replying on default response queue to replying
  on a bespoke queue for the caller. (optional, default 10)"
  [connection
   {:keys [f threshold exchange queue queue-options threads
           prefetch-limit response]
    :or {exchange default-exchange
         response nil
         prefetch-limit default-prefetch-limit
         queue-options default-queue-options
         threads wire-up/default-thread-count
         threshold default-threshold}}]

  (let [in-chan (async/chan)
        out-chan (async/chan)
        f (realize-symbol-or-self f)
        ignore-no-reply-to? (not response)
        streaming? (= response :streaming)
        rabbit-ch (wire-up/incoming-service connection
                                            exchange
                                            queue
                                            queue-options
                                            in-chan
                                            out-chan
                                            ignore-no-reply-to?
                                            prefetch-limit)]
    (if streaming?
      (wire-up/start-streaming-responder! connection
                                          in-chan
                                          out-chan
                                          f
                                          threshold
                                          threads)
      (wire-up/start-responder! in-chan
                                out-chan
                                f
                                threads))
    {:rabbit-channel rabbit-ch
     :async-channels {:in in-chan
                      :out out-chan}}))

(defn init-external-service!
  "Initializes an external service to send requests to via a queue
  which may reply with results.

  Arguments:
  * `connection`: A rabbitmq connection
  * `external-service-options`: A map defining the service

  Valid keys for the `external-service-options` are:
  * `:channel`: A fully qualified symbol for the core.async channel that
  requests to the service are placed on. Separately, this channel
  should be passed to `wire-up/async->fn` or
  `wire-up/async->fire-and-forget-fn` to complete the setup for
  calling the external service. (required)
  * `:queue`: The name of the queue used by the service. (required)
  * `:queue-options`: The options map passed to `langohr.queue/declare`
  for the queue. (optional, default {:auto-delete false :durable true
  :exclusive false})
  * `:response`: How responses are received. Key should be `nil`,
  `:streaming` or `true`. `nil` means no response will be
  received. `:streaming` means multiple values may come from the
  response channel before it is closed. `true` means only one value
  will come from the response channel. Should match the value used on
  the other side. (optional, default `nil`)
  * `:exchange`: The name of the exchange. (optional, default `\"\"`)
  * `:timeout`: The number of milliseconds to wait for a response from
  the service before giving up. (optional, default 1000)"
  [connection
   {:keys [response exchange queue queue-options timeout channel]
    :or {exchange default-exchange
         queue-options default-queue-options
         timeout default-timeout
         response nil}}]

  (let [channel (realize-symbol-or-self channel)
        rabbit-ch (cond
                    (= response :streaming) (wire-up/streaming-external-service
                                             connection
                                             exchange
                                             queue
                                             queue-options
                                             timeout
                                             channel)
                    (not response) (wire-up/external-service-fire-and-forget
                                    connection
                                    exchange
                                    queue
                                    queue-options
                                    channel)
                    :else (wire-up/external-service
                           connection
                           exchange
                           queue
                           queue-options
                           timeout
                           channel))]
    {:rabbit-channel rabbit-ch}))

(defn init-incoming-event!
  "Initializes a handler for incoming messages from a rabbit topic.

  Arguments:
  * `connection`: A rabbitmq connection
  * `incoming-event-options`: A map defining the event handler

  Valid keys for the `incoming-event-options` are:
  * `:f`: A fully qualified symbol for the function that will be called
  for every message received for the subscribed topic. It must be a
  function of one argument, which will be a serializable Clojure
  value. (required)
  * `:routing-key`: The routing key being listened for. (required)
  * `:queue`: The name of the queue that will be bound to the
  routing-key. (required)
  * `:queue-options`: The options map passed to `langohr.queue/declare`
  for the queue. (optional, default {:auto-delete false :durable true
  :exclusive false})
  * `:exchange`: The name of the exchange. (optional, default `\"\"`)
  * `:prefetch-limit`: The number of messages to prefetch from the
  queue. (optional, default 1).
  * `:threads`: The number of threads to run, each listening for and
  handling events. (optional, default 10)
  * `:timeout`: The number of milliseconds to wait for a message being
  accepted for processing before nacking. (optional, default 1000)"
  [connection
   {:keys [queue queue-options exchange routing-key timeout f
           prefetch-limit threads]
    :or {exchange default-exchange
         prefetch-limit default-prefetch-limit
         queue-options default-queue-options
         threads wire-up/default-thread-count
         timeout default-timeout}}]

  (let [f (realize-symbol-or-self f)
        channel (async/chan 100)
        rabbit-ch (wire-up/incoming-events-channel connection
                                                   queue
                                                   queue-options
                                                   exchange
                                                   routing-key
                                                   channel
                                                   timeout
                                                   prefetch-limit)]
    (wire-up/start-event-handler! channel f threads)

    {:rabbit-channel rabbit-ch
     :async-channels {:in channel}}))

(defn init-outgoing-event!
  "Initializes a core.async channel for sending messages to a topic.

  Arguments:
  * `connection`: A rabbitmq connection
  * `outgoing-event-options`: A map defining the channel and topic.

  Valid keys for the `outgoing-event-options` are:
  * `:routing-key`: The routing key used for outgoing
  messages. (required)
  * `:channel`: A fully qualified symbol for the core.async channel from
  which messages will be sent to rabbit. Only serializable values may
  be placed on that channel. (required)
  * `:exchange`: The name of the exchange. (optional, default `\"\"`)"
  [connection
   {:keys [exchange routing-key channel]
    :or {exchange default-exchange}}]

  (let [out-channel (realize-symbol-or-self channel)
        rabbit-ch (wire-up/outgoing-events-channel
                   connection
                   exchange
                   routing-key
                   out-channel)]

    {:rabbit-channel rabbit-ch}))

(def outgoing-jobs-initialized?
  (atom false))

(defn init-outgoing-job!
  "Initializes a queue for sending job requests on.

  Arguments:
  * `connection`: A rabbitmq connection
  * `outgoing-job-options`: A map describing the job channel and queue.

  Valid keys for the `outgoing-job-options` are:
  * `:jobs-chan`: A fully qualified symbol for the core.async channel
  from which messages will be sent to rabbit. Only serializable values
  may be placed on that channel. (required)
  * `:queue`: The name of the queue that will be bound to the
  routing-key. (required)
  * `:queue-options`: The options map passed to `langohr.queue/declare`
  for the queue. (optional, default {:auto-delete false :durable true
  :exclusive false})
  * `:exchange`: The name of the exchange. (optional, default `\"\"`)"
  [connection {:keys [jobs-chan queue queue-options exchange]
               :or {queue-options default-queue-options
                    exchange default-exchange}}]
  (let [jobs-chan (realize-symbol-or-self jobs-chan)
        outgoing-ch (langohr.channel/open connection)]
    (lq/declare outgoing-ch queue queue-options)
    (kehaar.core/async=>rabbit jobs-chan outgoing-ch exchange queue)
    (when (compare-and-set! outgoing-jobs-initialized? false true)
      (let [response-ch (langohr.channel/open connection)
            response-queue (lq/declare-server-named response-ch)
            worker-ch (langohr.channel/open connection)
            worker-queue (lq/declare-server-named response-ch)]

        (lb/qos response-ch 1)
        (lq/bind response-ch
                 response-queue
                 jobs/kehaar-exchange
                 {:routing-key "kehaar-job.*"})
        (lc/subscribe response-ch response-queue
                      (fn [ch metadata ^bytes payload]
                        (let [m (kehaar.core/read-payload payload)
                              {:keys [::jobs/routing-key
                                      ::jobs/message]} m]
                          (when-let [handler (get-in @jobs/jobs [routing-key :handler])]
                            (if (= ::jobs/complete message)
                              (do
                                (jobs/complete! routing-key)
                                (when (zero? (get-in @jobs/jobs [routing-key :workers]))
                                  (jobs/cancel! routing-key)))
                              (let [result (handler message)]
                                (when (= ::jobs/stop result)
                                  (jobs/cancel! routing-key)))))))
                      {:auto-ack true})

        (lb/qos worker-ch 1)
        (lq/bind worker-ch
                 worker-queue
                 jobs/kehaar-exchange
                 {:routing-key "kehaar-worker"})
        (lc/subscribe worker-ch worker-queue
                      (fn [ch metadata ^bytes payload]
                        (let [m (kehaar.core/read-payload payload)
                              {:keys [::jobs/routing-key]} m]
                          (when (get @jobs/jobs routing-key)
                            (jobs/add-worker! routing-key))))
                      {:auto-ack true})))
    {:rabbit-channel outgoing-ch}))

(defn init-incoming-job!
  "Initializes a service that accepts messages on a queue and will
  send results back asynchronously.

  Arguments:
  * `connection`: A rabbitmq connection
  * `incoming-job-options`: A map defining the service

  Valid keys for the `outgoing-event-options` are:
  * `:f`: A fully qualified symbol for the function that will be
  called for every message received for the subscribed topic. It must
  be a function of three arguments, which will be a core.async a
  channel to put results on, the routing-key for the job that may be
  passed to further job services, and a serializable Clojure
  value. The function must close the core.async channel when
  finished. (required)
  * `:queue`: The name of the queue that will be bound to the
  routing-key. (required)
  * `:queue-options`: The options map passed to `langohr.queue/declare`
  for the queue. (optional, default {:auto-delete false :durable true
  :exclusive false})
  * `:prefetch-limit`: The number of messages to prefetch from the
  queue. (optional, default 1).
  * `:threads`: The number of threads to run, each listening for and
  handling events. (optional, default 10)"
  [connection {:keys [f queue queue-options prefetch-limit threads]
               :or {prefetch-limit default-prefetch-limit
                    queue-options default-queue-options
                    threads wire-up/default-thread-count}}]
  (when (compare-and-set! jobs/workers-chan-initialized? false true)
    (let [work-ch (lchan/open connection)]
      (kehaar.core/async=>rabbit jobs/workers-chan
                                 work-ch
                                 jobs/kehaar-exchange
                                 "kehaar-worker")))
  (let [f (realize-symbol-or-self f)
        in-chan (async/chan)
        ch (lchan/open connection)]
    (lq/declare ch queue queue-options)
    (when prefetch-limit
      (lb/qos ch prefetch-limit))
    (kehaar.core/rabbit=>async ch queue in-chan queue-options 100)
    (wire-up/start-jobs-handler! ch
                                 in-chan
                                 f
                                 threads)
    {:rabbit-channel ch
     :async-channels {:in in-chan}}))

(defn shutdown-part!
  "Closes rabbit channels and core.async channels created by any of
  the `init-*!` functions."
  [{:keys [rabbit-channel async-channels]}]
  (let [{:keys [in out]} async-channels]
    (when in
      (async/close! in))
    (when out
      (async/close! out)))
  (when-not (rmq/closed? rabbit-channel)
    (rmq/close rabbit-channel)))

(defn shutdown!
  "Closes rabbit channels and core.async channels created by `init!`."
  [parts]
  (doseq [part parts]
    (shutdown-part! part)))

(defn init!
  "Initializes multiple services in one go. Takes a rabbitmq
  connection and a configuration map.

  The configuration map can have the keys `event-exchanges`,
  `incoming-services`, `external-services`, `incoming-events`, and
  `outgoing-events`. Each key's value should be a sequence of maps
  appropriate for its initializer.

  Returns a collection of resources that may be closed by `shutdown!`."
  [connection configuration]
  (let [{:keys [event-exchanges
                incoming-services
                external-services
                incoming-events
                outgoing-events
                incoming-jobs
                outgoing-jobs]}
        configuration
        states (atom [])]
    (init-exchange! connection {:exchange jobs/kehaar-exchange})

    (rmq/on-queue-recovery
     connection
     (fn [^String old-name ^String new-name]
       (when-let [q (rq/get-queue old-name)]
         (log/info "RabbitMQ connection recovery."
                   "Renamed response queue for" q
                   "from" old-name "to" new-name)
         (rq/set-response-queue! q new-name))))

    (doseq [exchange event-exchanges]
      (swap! states conj (init-exchange! connection exchange)))
    (doseq [incoming-service incoming-services]
      (swap! states conj (init-incoming-service! connection incoming-service)))
    (doseq [external-service external-services]
      (swap! states conj (init-external-service! connection external-service)))
    (doseq [incoming-event incoming-events]
      (swap! states conj (init-incoming-event! connection incoming-event)))
    (doseq [outgoing-event outgoing-events]
      (swap! states conj (init-outgoing-event! connection outgoing-event)))
    (doseq [incoming-job incoming-jobs]
      (swap! states conj (init-incoming-job! connection incoming-job)))
    (doseq [outgoing-job outgoing-jobs]
      (swap! states conj (init-outgoing-job! connection outgoing-job)))

    @states))
