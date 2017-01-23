(ns kehaar.core
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [langohr.basic :as lb]
            [langohr.consumers :as lc]
            [langohr.queue :as lq]
            [clojure.tools.logging :as log]
            [kehaar.async :refer [bounded>!!]]))

(defn read-payload
  "Unsafely read a byte array as edn."
  [^bytes payload]
  (-> payload
      (String. "UTF-8")
      edn/read-string))

(defn channel-handler
  "Returns a RabbitMQ message handler function which puts each
  incoming message on `channel`."
  ([channel exchange timeout]
   (channel-handler channel exchange timeout false))
  ([channel exchange timeout close-channel?]
   (fn [ch {:keys [delivery-tag] :as metadata} ^bytes payload]
     (try
       (let [message (read-payload payload)]
         (cond
           (nil? message)
           ;; don't requeue nil, it's invalid
           [:nack delivery-tag false]

           (= ::stop message)
           ;; ack it and close the channel if close-channel?
           (do
             (if close-channel?
               (async/close! channel)
               (bounded>!! channel {:message message
                                    :metadata metadata} timeout))
             [:ack delivery-tag])

           :else
           (case (bounded>!! channel {:message  message
                                      :metadata metadata} timeout)
             ;; assume we're busy, requeue timed out
             :kehaar.async/timeout
             [:nack delivery-tag true]

             ;; successfully put, ack now
             true
             [:ack delivery-tag]

             ;; channel closed, cancel subscribe
             false
             [:cancel])))

       (catch Throwable t
         (log/error t "Kehaar: fn->handler-fn: payload did not parse"
                    (String. payload "UTF-8"))
         ;; don't requeue parse errors
         [:nack delivery-tag false])))))

(defn- ack-nack-or-cancel [channel queue close-channel? [op delivery-tag requeue :as ret]]
  (case op
    :ack    (lb/ack  channel delivery-tag)
    :nack   (do
              (lb/nack channel delivery-tag false requeue)
              (Thread/sleep 1000))
    :cancel (when close-channel?
              (try (lq/delete channel queue)
                   (catch Exception _))) ; typically this fails when it's already gone
    ;; otherwise, let's log that
    (log/warn "Kehaar: I don't know how to process this:" ret
              "I'm designed to process messages containing :ack, :nack,
              or :cancel.")))

(defn rabbit=>async
  "Subscribes to the RabbitMQ queue, taking each payload, decoding as
  edn, and putting the result onto the async channel. This propagates
  backpressure back to rabbit by using nacks."
  ([rabbit-channel queue channel]
   (rabbit=>async rabbit-channel queue channel {} 100))
  ([rabbit-channel queue channel options timeout]
   (rabbit=>async rabbit-channel queue channel options timeout false))
  ([rabbit-channel queue channel options timeout close-channel?]
   (lc/subscribe rabbit-channel
                 queue
                 (comp (partial ack-nack-or-cancel rabbit-channel queue close-channel?)
                       (channel-handler channel "" timeout close-channel?))
                 (assoc options :auto-ack false))))

(defmacro go-handler
  "A macro that runs code in `body`, with `binding` bound to each
  message coming in on `channel`."
  [[binding channel] & body]
  `(let [channel# ~channel]
     (async/go-loop []
       (let [ch-message# (async/<! channel#)]
         (if (nil? ch-message#)
           (log/warn "Kehaar: go handler" '~channel "is closed.")
           (do
             (try
               (let [~binding ch-message#]
                 ~@body)
               (catch Throwable t#
                 (log/error t# "Kehaar: caught an exception in go-handler:" '~channel)))
             (recur)))))))

(defn async=>rabbit
  "Forward all messages on channel to the RabbitMQ queue. Messages
  should look like:

  ```
  {:message  {...}  ;; message payload
  :metadata {...}} ;; rabbit metadata (optional)
  ```"
  ([channel rabbit-channel queue]
   (async=>rabbit channel rabbit-channel "" queue))
  ([channel rabbit-channel exchange queue]
   (go-handler [{:keys [message metadata]} channel]
     (lb/publish rabbit-channel exchange queue (pr-str message)
                 metadata))))

(defn async=>rabbit-with-reply-to
  "Forward all messages on channel to the RabbitMQ queue specified in
  `:reply-to` metadata. Messages should look like:

  ```
  {:message  {...}  ;; message payload
  :metadata {:repy-to \"queue\"
             ...}} ;; rabbit metadata
  ```

  If there is no `:reply-to`, a warning will be logged, unless
  `ignore-no-reply-to` is `true`"
  ([channel rabbit-channel]
   (async=>rabbit-with-reply-to channel rabbit-channel ""))
  ([channel rabbit-channel exchange]
   (async=>rabbit-with-reply-to channel rabbit-channel "" false))
  ([channel rabbit-channel exchange ignore-no-reply-to]
   (go-handler [{:keys [message metadata]} channel]
     (if-let [reply-to (:reply-to metadata)]
       (lb/publish rabbit-channel exchange reply-to (pr-str message)
                   (assoc metadata :mandatory true))
       (when-not ignore-no-reply-to
         (log/warn "Kehaar: No reply-to in metadata."
                   (pr-str message)
                   (pr-str metadata)))))))

(defn thread-handler
  [channel f threads]
  (dotimes [_ threads]
    (async/thread
      (loop []
        (let [ch-message (async/<!! channel)]
          (if (nil? ch-message)
            (log/trace "Kehaar: thread handler is closed.")
            (do
              (try
                (f ch-message)
                (catch Throwable t
                  (log/error t "Kehaar: caught exception in thread-handler")))
              (recur))))))))

(defn responder-fn
  "Create a function that takes map of message and metadata, calls `f`
  on message, and redirects the return to `out-channel`. This is used
  by `responder`. Handles two cases: async channel return and regular
  value return."
  [out-channel f]
  (fn [{:keys [message metadata]}]
    (let [return (f message)]
      (if (satisfies? clojure.core.async.impl.protocols/ReadPort return)
        (let [metadata-channel (async/chan 1000 (map (fn [message]
                                                       {:message message
                                                        :metadata metadata})))]
          (async/pipe return metadata-channel true)
          (async/pipe metadata-channel out-channel false))

        (async/>!! out-channel {:message return
                                :metadata metadata})))))

(defn streaming-responder-fn
  "Create a function that takes map of message and metadata, calls `f`
  on message, and redirects the return to `out-channel`. If the size
  of the response is going to be larger than `threshold`, they are
  placed on a bespoke queue and the queue's name is placed on the
  `out-channel` instead of the results. Sends a `::stop` message when
  the response sequence is exhausted."
  [connection out-channel f threshold]
  (fn [{:keys [message metadata]}]
    (let [return (f message)
          stream-active? (atom true)]
      (loop [sent-count 0
             responses return
             put-fn (fn [v]
                      (async/>!! out-channel
                                 {:message v
                                  :metadata metadata}))]
        (cond
          (empty? responses)            ; we're done
          (put-fn ::stop)

          (= sent-count threshold)      ; set up bespoke queue and start using it
          (let [ch (langohr.channel/open connection)
                response-queue (langohr.queue/declare-server-named
                                ch
                                {:exclusive false
                                 :auto-delete true
                                 :durable false})
                return-listener (lb/return-listener
                                 (fn [reply-code reply-text exchange routing-key
                                      properties body]
                                   (reset! stream-active? false)))
                put-fn (fn [v]
                         (when @stream-active?
                           (lb/publish ch "" response-queue (pr-str v)
                                       (assoc metadata :mandatory true))))]
            ;; add return listener
            (.addReturnListener ch return-listener)

            (async/>!! out-channel {:message {::response-queue response-queue}
                                    :metadata metadata})

            (put-fn (first responses))
            (recur (inc sent-count) (rest responses) put-fn))

          @stream-active?      ; send the response
          (do
            (put-fn (first responses))
            (recur (inc sent-count) (rest responses) put-fn))

          :else                         ; client closed the channel, stop sending
          (log/debug "Client closed streaming channel"))))))
