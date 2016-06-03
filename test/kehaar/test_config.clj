(ns kehaar.test-config)

(def rmq-config
  {:host (or (System/getenv "RABBITMQ_HOST") "localhost")
   :port (Integer. (or (System/getenv "RABBITMQ_PORT") 5672))})
