(ns kehaar.configure
  (:require [kehaar.wire-up :as wire-up]
            [clojure.core.async :as async]))

(def default-thread-count 10)

(defn require-ns [sym]
  (let [ns' (symbol (namespace sym))]
    (when-not (find-ns ns')
      (require ns'))))

(defn configure! [connection configuration]
  (let [{:keys [incoming-services external-services incoming-events
                outgoing-events threads]}
        configuration]
    (doseq [{:keys [f threshold queue queue-options streaming?]}
            incoming-services]
      (require-ns f)
      (let [in-chan (async/chan)
            out-chan (async/chan)
            f-var (find-var f)]
        (wire-up/incoming-service connection
                                  queue
                                  queue-options
                                  in-chan
                                  out-chan)
        (if streaming?
          (wire-up/start-streaming-responder! connection
                                              in-chan
                                              out-chan
                                              f-var
                                              threshold
                                              (or threads
                                                  default-thread-count))
          (wire-up/start-responder! in-chan
                                    out-chan
                                    f-var
                                    (or threads default-thread-count)))))
    (doseq [{:keys [streaming? exchange queue queue-options timeout f]}
            external-services]
      (require-ns f)
      (let [channel (async/chan 100)]
        (let [external-service-fn (if streaming?
                                    wire-up/streaming-external-service
                                    wire-up/external-service)]
          (alter-var-root (find-var f)
                          (constantly (wire-up/async->fn channel)))
          (external-service-fn connection
                               exchange
                               queue
                               queue-options
                               timeout
                               channel))))
    (doseq [{:keys [queue queue-options topic routing-key timeout f threads]}
            incoming-events]
      (require-ns f)
      (let [channel (async/chan 100)]
        (wire-up/incoming-events-channel connection
                                         queue
                                         queue-options
                                         topic
                                         routing-key
                                         channel
                                         timeout)
        (wire-up/start-event-handler! channel (find-var f)
                                      (or threads default-thread-count))))
    (doseq [{:keys [topic routing-key channel]}
            outgoing-events]
      (require-ns channel)
      (wire-up/outgoing-events-channel connection
                                       topic
                                       routing-key
                                       (var-get (find-var channel))))))

;;; TODO: return all channels created... or register an exit handler
;;; that will close them?

;;; TODO: provide some defaults (like :or {exchange ""}) for some
;;; sensible defaults: exchange, timeout, queue-options(?)
