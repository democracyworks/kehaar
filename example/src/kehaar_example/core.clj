(ns kehaar-example.core
  (:require [kehaar.rabbitmq :as kehaar-rabbit]
            [kehaar.wire-up :as wire-up]
            [kehaar.configure :as configure]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

;;; In this example, a service is created to provide Fibonacci
;;; numbers. The function to calculate Fibonacci numbers *could* be
;;; pure, but it isn't in this case (so it can demonstrate sending
;;; separate messages on topic exchanges). The function only deals
;;; with numbers though, not messages or parsing or queues.

(declare get-fib)

;;; These channels are for events on the topic exchange. The outgoing
;;; channel will take any messages it receives and put them on the
;;; topic exchange. The incoming channel will receive any messages
;;; read off the topic exchange.
(def outgoing-fib-events (async/chan 100))

(def config {:incoming-services [{:queue "calculate-fib"
                                  :queue-options {:auto-delete true}
                                  :f 'kehaar-example.core/fib}]
             :external-services [{:exchange ""
                                  :queue "calculate-fib"
                                  :queue-options {:auto-delete true}
                                  :timeout 5000
                                  :f 'kehaar-example.core/get-fib}]
             :incoming-events [{:queue "fibs-incoming"
                                :queue-options {:auto-delete true}
                                :topic "events"
                                :routing-key "fib-event"
                                :timeout 100
                                :f 'kehaar-example.core/log-fib-event}]
             :outgoing-events [{:topic "events"
                                :routing-key "fib-event"
                                :channel 'kehaar-example.core/outgoing-fib-events}]})

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

(defn setup []
  (let [;; Connect to the rabbit server (retrying up to 5 times if the
        ;; connection fails)
        conn (kehaar-rabbit/connect-with-retries)

        ;; Declare the events exchange.
        events-ch (wire-up/declare-events-exchange conn
                                                   "events"
                                                   "topic"
                                                   {:auto-delete true})]
    (configure/configure! conn config)))

;;; This function logs Fibonacci numbers received through the events
;;; exchange.
(defn log-fib-event [fib-result]
  (log/info "Received event:" fib-result))

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
