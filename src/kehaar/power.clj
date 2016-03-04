(ns kehaar.power
  (:require [clojure.core.async :as async]
            [kehaar.wire-up :as wire-up]
            [kehaar.rabbitmq]
            [langohr.core :as rmq]
            [clojure.tools.logging :as log]))

(def std-queue-config {:exclusive false :durable true :auto-delete false})

(defn rabbit-close [resource]
  (when-not (rmq/closed? resource)
    (rmq/close resource)))

(defn handle-errors
  [queue-name handler]
  (fn [message]
    (try
      (handler message)
      (catch IllegalArgumentException semantic-error
        (log/error semantic-error (str "Semantic error in " queue-name))
        {:status :error
         :error {:type :semantic
                 :message (.getMessage semantic-error)
                 :request message}})
      (catch clojure.lang.ExceptionInfo validation-error
        (log/error validation-error (str "Validation error in " queue-name))
        {:status :error
         :error {:type :validation
                 :message (.getMessage validation-error)
                 :request message}})
      (catch Throwable t
        (log/error t (str "Error in " queue-name))
        {:status :error
         :error {:type :server
                 :message (str "Unknown server error: " (.getMessage t))
                 :request message}}))))

(defn init-events-exchange [connection]
  (let [ch (wire-up/declare-events-exchange connection
                                            "events"
                                            "topic"
                                            {:durable true :auto-delete false})]
    (fn tear-down []
      (rabbit-close ch))))

;; We want to lock all connect/disconnect operations. Stateful/IO
;; operations is classic mutex territory so let's just lock. Typically
;; it's only done when the server is starting or shutting down.
(defonce lock (Object.))

(defonce tear-downers (atom []))

(defn init-and-save [init connection]
  (locking lock
    (let [tear-down (init connection)]
      (swap! tear-downers conj tear-down))))

(defn connect-incoming-service [f queue-name connection]
  (let [;; we don't like queuing up incoming, prefer backpressure
        request-ch (async/chan)
        ;; responses can be queued, maybe it's faster?
        response-ch (async/chan 1000)
        rabbit-ch (wire-up/incoming-service connection
                                            queue-name
                                            std-queue-config
                                            request-ch
                                            response-ch)]
    (wire-up/start-responder! request-ch
                              response-ch
                              f)

    (fn tear-down []
      (rabbit-close rabbit-ch)
      (async/close! request-ch)
      (async/close! response-ch))))

(defn connect-outgoing-events [event-chan topic-name connection]
  (let [;; go-loop will die when this closes
        control-chan (async/chan)
        async-chan (async/chan 1000)
        rabbit-ch (wire-up/outgoing-events-channel connection
                                                   "events"
                                                   topic-name
                                                   async-chan)]
    (async/go-loop []
      (async/alt!
        control-chan nil
        event-chan ([v]
                    (async/>! async-chan v)
                    (recur))))
    (fn tear-down []
      ;; we don't close the event-chan, we just stop taking from it
      (async/close! control-chan)
      (async/close! async-chan)
      (rabbit-close rabbit-ch))))

(defn connect-external-service [req-chan queue-name connection]
  (let [ ;; go-loop will die when this closes
        control-chan (async/chan)
        async-chan (async/chan 1000)
        rabbit-ch (wire-up/external-service connection
                                            ""
                                            queue-name
                                            std-queue-config
                                            10000
                                            async-chan)]
    (async/go-loop []
      (async/alt!
        control-chan nil
        req-chan ([v]
                  (async/>! async-chan v)
                  (recur))))
    (fn tear-down []
      ;; we don't close the req-chan, we just stop taking from it
      (async/close! control-chan)
      (async/close! async-chan)
      (rabbit-close rabbit-ch))))

(defn connect-incoming-event-handler [f queue-name topic-name connection]
  (let [ ;; we don't like queuing up incoming, prefer backpressure
        events-ch (async/chan)
        rabbit-ch (wire-up/incoming-events-channel
                   connection
                   queue-name
                   std-queue-config
                   "events"
                   topic-name
                   events-ch
                   5000)]

    (wire-up/start-event-handler! events-ch f)

    (fn tear-down []
      (rabbit-close rabbit-ch)
      (async/close! events-ch))))

(defn disconnect-rabbit []
  (locking lock
    (log/info "Disconnecting rabbit if already connected.")
    (doseq [tear-down @tear-downers]
      (tear-down))
    (reset! tear-downers [])))

(defn connect-rabbit [max-retries config]
  (locking lock
    (disconnect-rabbit)
    (log/info "Connecting Rabbit.")
    (let [conn (kehaar.rabbitmq/connect-with-retries config max-retries)
          exchange-tear-down (init-events-exchange conn)]
      (doseq [ns (all-ns)
              var (vals (ns-interns (the-ns ns)))]
        (when-let [init (::init (meta var))]
          (log/info "Initializing " var)
          (init-and-save init conn)))
      (swap! tear-downers conj
             exchange-tear-down
             (fn tear-down []
               (rabbit-close conn))))
    (log/info "Done connecting Rabbit.")))

(defmacro defn-service-handler
  "Define a service handler. The handler can be called as a
  function. If/when rabbitmq is connected, it also will be connected
  to the queue (created if needed). It will be disconnected when
  rabbit disconnects."
  [handler-name queue-name doc-string message-binding
   & body]
  (assert (symbol? handler-name))
  (assert (string? doc-string))
  (assert (vector? message-binding))
  (assert (= 1 (count message-binding)))
  (assert (string? queue-name))
  `(do
     (let [f# (handle-errors ~queue-name (fn ~message-binding ~@body))]
       (defn ~handler-name
         ~doc-string
         [~'message]
         (f# ~'message)))
     (let [v# (var ~handler-name)
           ;; use the var to allow redefinition
           init# (partial connect-incoming-service v# ~queue-name)]
       (alter-meta! v# assoc
                    ::init init#
                    ::type :incoming-service
                    ::queue-name ~queue-name
                    :arglists '(~message-binding))
       v#)))

(defmacro defonce-outgoing-events-chan
  "Define an outgoing events chan. If/when rabbit mq is connected, the
  chan will be piped to the events topic specified."
  [chan-name topic-name c]
  (assert (symbol? chan-name))
  (assert (string? topic-name))
  `(do
     (defonce ~chan-name ~c)
     (let [v# (var ~chan-name)
           init# (partial connect-outgoing-events ~chan-name ~topic-name)]
       (alter-meta! v# assoc
                    ::init init#
                    ::type :outgoing-events
                    ::topic-name ~topic-name)
       v#)))

(defmacro defn-external-service
  "Define a function that calls an external service. The function will
  return a core.async channel with the response."
  [fn-name queue-name doc-string]
  (assert (symbol? fn-name))
  (assert (string? doc-string))
  (assert (string? queue-name))
  (let [cname (symbol (str fn-name "__channel"))]
    `(do
       (defonce ~cname (async/chan 1000))
       (let [f# (wire-up/async->fn ~cname)]
         ;; using a defn will set up all the nice metadata like args.
         (defn ~fn-name
           ~doc-string
           [message#]
           (f# message#)))
       (let [v# (var ~fn-name)
             init# (partial connect-external-service ~cname ~queue-name)]
         (alter-meta! v# assoc
                      ::init init#
                      ::type :external-service
                      ::queue-name ~queue-name
                      :arglists '([message]))
         v#))))

(defmacro defn-incoming-event-handler
  "Define a handler that waits for and responds to incoming events for
  a given topic."
  [handler-name topic-name queue-name
   doc-string
   message-binding
   & body]
  (assert (symbol? handler-name))
  (assert (string? doc-string))
  (assert (vector? message-binding))
  (assert (= 1 (count message-binding)))
  (assert (string? queue-name))
  (assert (string? topic-name))
  `(do
     (let [f# (handle-errors ~queue-name (fn ~message-binding ~@body))]
       (defn ~handler-name
         ~doc-string
         [message#]
         (f# message#)))
     (let [v# (var ~handler-name)
           ;; use the var to allow redefinition
           init# (partial connect-incoming-event-handler v# ~queue-name ~topic-name)]
       (alter-meta! v# assoc
                    ::init init#
                    ::type :incoming-events
                    ::topic-name ~topic-name
                    ::queue-name ~queue-name
                    :arglists '(~message-binding))
       v#)))
