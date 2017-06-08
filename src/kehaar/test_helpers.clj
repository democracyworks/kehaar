(ns kehaar.test-helpers
  (:require [clojure.core.async :as async]))

(defn responder-test
  ([in-channel out-channel] (responder-test in-channel out-channel 10000))
  ([in-channel out-channel timeout]
   (fn [message]
     (let [request-result (async/alt!! [[in-channel {:message message}]] ::sent
                                       (async/timeout timeout) ::timeout)]
       (case request-result
         ::sent (:message (async/alt!! out-channel ([v] v)
                                       (async/timeout timeout) ::timeout))
         ::timeout ::timeout)))))
