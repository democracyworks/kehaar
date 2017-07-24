(ns kehaar-example.core
  (:require [kehaar.rabbitmq :as kehaar-rabbit]
            [kehaar.wire-up :as wire-up]
            [kehaar.configured :as configured]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

;;; In this example, a service is created to provide Fibonacci
;;; numbers. The function to calculate Fibonacci numbers *could* be
;;; pure, but it isn't in this case (so it can demonstrate sending
;;; separate messages on topic exchanges). The function only deals
;;; with numbers though, not messages or parsing or queues.

(def get-fib-ch (async/chan))
(def get-fib (wire-up/async->fn get-fib-ch))

;;; These channels are for events on the topic exchange. The outgoing
;;; channel will take any messages it receives and put them on the
;;; topic exchange. The incoming channel will receive any messages
;;; read off the topic exchange.
(def outgoing-fib-events (async/chan 100))

;;; Calculate the nth Fibonacci number. Also puts the result onto the
;;; `outgoing-fib-events` channel, which is forwarded to the
;;; "fib-event" topic on the "events" exchange.
(defn fib
  ([n] (fib n 0 1))
  ([n a b]
   (if (zero? n)
     (do
       (async/put! outgoing-fib-events b)
       b)
     (recur (dec n) b (+ a b)))))

;;; This function logs Fibonacci numbers received through the events
;;; exchange.
(defn log-fib-event [fib-result]
  (log/info "Received event:" fib-result))

(def config
  {:incoming-services [{:queue "calculate-fib"
                        :f fib
                        :response true}]
   :external-services [{:queue "calculate-fib"
                        :timeout 5000
                        :channel get-fib-ch
                        :response true}]
   :incoming-events [{:queue "fibs-incoming"
                      :exchange "events"
                      :routing-key "fib-event"
                      :timeout 100
                      :f log-fib-event}]
   :outgoing-events [{:exchange "events"
                      :routing-key "fib-event"
                      :channel outgoing-fib-events}]
   :event-exchanges [{:exchange "events"
                      :type "topic"
                      :options {:auto-delete true}}]})

(defn setup []
  (let [connection (kehaar-rabbit/connect-with-retries)]
    (configured/init! connection config)))

(defn -main [& args]
  (log/info "Starting up...")

  (setup)

  (log/info "Started")

  ;; Make 500 requests to the service.
  (doseq [_ (range 10)
          n (range 50)]
    (log/info "Requesting fib:" n)
    (let [fib-response (get-fib n)]
      (async/go
        (log/info "Got fib:" n "Result:" (async/<! fib-response)))))

  ;; Give it a few seconds then exit
  (Thread/sleep 5000)
  (System/exit 0))
