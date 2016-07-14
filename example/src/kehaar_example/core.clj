(ns kehaar-example.core
  (:require [kehaar.rabbitmq :as kehaar-rabbit]
            [kehaar.wire-up :as wire-up]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

;;; In this example, a service is created to provide Fibonacci
;;; numbers. The function to calculate Fibonacci numbers *could* be
;;; pure, but it isn't in this case (so it can demonstrate sending
;;; separate messages on topic exchanges). The function only deals
;;; with numbers though, not messages or parsing or queues.

;;; First we set up the core.async channels we need.

;;; These channels are for the Fibonacci service. One channel for
;;; incoming requests, and the other for sending responses back. These
;;; two channels will be passed to `wire-up/incoming-service` and
;;; `wire-up/start-responder!` to set the service in motion.
(def incoming-fib-requests (async/chan 100))
(def outgoing-fib-responses (async/chan 100))

;;; This channel is for the Fibonacci client. The channel is then
;;; passed to `wire-up/async->fn` which returns a function that takes
;;; the argument for `fib` and returns a core.async channel to listen
;;; for the response on.
(def outgoing-fib-requests (async/chan 100))
(def get-fib (wire-up/async->fn outgoing-fib-requests))

;;; These channels are for events on the topic exchange. The outgoing
;;; channel will take any messages it receives and put them on the
;;; topic exchange. The incoming channel will receive any messages
;;; read off the topic exchange.
(def outgoing-fib-events (async/chan 100))
(def incoming-fib-events (async/chan 100))

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
                                                   {:auto-delete true})

        ;; Send all messages on the "fib-event" topic to the
        ;; `incoming-fib-events` core.async channel (with a timeout of
        ;; 100ms).
        event-listener-ch (wire-up/incoming-events-channel conn
                                                           "fibs-incoming"
                                                           {:auto-delete true}
                                                           "events"
                                                           "fib-event"
                                                           incoming-fib-events
                                                           100)

        ;; Send all messages on the `outgoing-fib-events` core.async
        ;; channel to the "fib-event" topic on the "events" exchange.
        event-sender-ch (wire-up/outgoing-events-channel conn
                                                         "events"
                                                         "fib-event"
                                                         outgoing-fib-events)

        ;; Provide Fibonacci numbers as a service through the
        ;; `incoming-fib-requests` and `outgoing-fib-responses`
        ;; core.async channels using the "calculate-fib" queue.
        incoming-service-ch (wire-up/incoming-service conn
                                                      "calculate-fib"
                                                      {:auto-delete true}
                                                      incoming-fib-requests
                                                      outgoing-fib-responses)

        ;; Use the Fibonacci numbers service through the
        ;; `outgoing-fib-requests` core.async channel. Uses the
        ;; "calculate-fib" queue the service set up.
        outgoing-service-ch (wire-up/external-service conn
                                                      ""
                                                      "calculate-fib"
                                                      {:auto-delete true}
                                                      1000
                                                      outgoing-fib-requests)]
    [events-ch
     event-listener-ch
     event-sender-ch
     incoming-service-ch
     outgoing-service-ch
     conn]))

;;; This function logs Fibonacci numbers received through the events
;;; exchange.
(defn log-fib-event [fib-result]
  (log/info "Received event:" fib-result))

(defn -main [& args]
  (log/info "Starting up...")

  (setup)

  ;; Start the Fibonacci numbers service. It uses the
  ;; `incoming-fib-requests` and `outgoing-fib-responses` core.async
  ;; channels for coordination, and the `fib` function to do the work.
  (wire-up/start-responder! incoming-fib-requests outgoing-fib-responses fib)

  ;; Start listening on incoming events from the `incoming-fib-events`
  ;; core.async channel and pass them to `log-fib-event`.
  (wire-up/start-event-handler! incoming-fib-events log-fib-event)

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
