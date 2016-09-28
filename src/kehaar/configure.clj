(ns kehaar.configure
  (:require [kehaar.wire-up :as wire-up]
            [clojure.core.async :as async]
            [langohr.basic :as lb]))

(def default-thread-count 10)

(defn require-ns [sym]
  (let [ns' (symbol (namespace sym))]
    (when-not (find-ns ns')
      (require ns'))))

(defn configure! [connection configuration]
  (let [rabbit-channels (atom #{})
        {:keys [incoming-services external-services incoming-events
                outgoing-events threads]}
        configuration]
    (doseq [{:keys [f threshold queue queue-options streaming?]}
            incoming-services]
      (require-ns f)
      (let [in-chan (async/chan)
            out-chan (async/chan)
            f-var (find-var f)
            thread-count (or threads default-thread-count)
            rabbit-ch (wire-up/incoming-service connection
                                                queue
                                                queue-options
                                                in-chan
                                                out-chan)]
        (lb/qos rabbit-ch thread-count)
        (swap! rabbit-channels conj rabbit-ch)

        (if streaming?
          (wire-up/start-streaming-responder! connection
                                              in-chan
                                              out-chan
                                              f-var
                                              threshold
                                              thread-count)
          (wire-up/start-responder! in-chan
                                    out-chan
                                    f-var
                                    (or threads default-thread-count)))))
    (doseq [{:keys [streaming? exchange queue queue-options timeout f]}
            external-services]
      (require-ns f)
      (let [channel (async/chan 100)
            external-service-fn (if streaming?
                                  wire-up/streaming-external-service
                                  wire-up/external-service)]
        (alter-var-root (find-var f)
                        (constantly (wire-up/async->fn channel)))
        (swap! rabbit-channels conj
               (external-service-fn connection
                                    exchange
                                    queue
                                    queue-options
                                    timeout
                                    channel))))
    (doseq [{:keys [queue queue-options topic routing-key timeout f threads]}
            incoming-events]
      (require-ns f)
      (let [channel (async/chan 100)
            rabbit-ch (wire-up/incoming-events-channel connection
                                                       queue
                                                       queue-options
                                                       topic
                                                       routing-key
                                                       channel
                                                       timeout)
            thread-count (or threads default-thread-count)]
        (lb/qos rabbit-ch thread-count)
        (swap! rabbit-channels conj rabbit-ch)
        (wire-up/start-event-handler! channel (find-var f) thread-count)))
    (doseq [{:keys [topic routing-key channel]}
            outgoing-events]
      (require-ns channel)
      (swap! rabbit-channels conj
             (wire-up/outgoing-events-channel connection
                                              topic
                                              routing-key
                                              (var-get (find-var channel)))))
    @rabbit-channels))

;;; TODO: provide some defaults (like :or {exchange ""}) for some
;;; sensible defaults: exchange, timeout, queue-options(?)
