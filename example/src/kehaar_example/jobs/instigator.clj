(ns kehaar-example.jobs.instigator
  "This is a service which requests jobs via the counting-job
  channel. Those jobs are worked on by the counter service, which
  subcontracts some of its work to the threes service."
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [kehaar.configured :as kehaar]
            [kehaar.jobs :as jobs]
            [kehaar.rabbitmq :as kehaar-rabbit]))

(def job-request-chan
  (async/chan))

(def request-job
  (jobs/async->job job-request-chan))

(def config
  {:outgoing-jobs [{:jobs-chan job-request-chan
                    :queue "counting-job"}]})

(defn make-job-handler [id n]
  (fn [message]
    (if (= 0 (rand-int 5))
      (do
        (log/info "Stopping job" id "/" n)
        ::jobs/stop)
      (log/info "Got response:" id "/" n "-" (pr-str message)))))

(defn setup []
  (let [connection (kehaar-rabbit/connect-with-retries)]
    (kehaar/init! connection config)))

(defn -main [& args]
  (log/info "Starting instigator")
  (setup)
  (log/info "Started")

  (let [id (rand-int 100)]

    (log/info "id:" id)

    (dotimes [n 10]
      (log/info "Requesting job" n)
      (request-job
       {:n n :id id}
       (make-job-handler id n)))

    (.join (Thread/currentThread))))
