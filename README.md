# democracyworks.kehaar

A Clojure library designed to pass messages between RabbitMQ and core.async.

## Usage

### Passing messages from RabbitMQ to core.async

```clojure
(ns example
  (:require [core.async :as async]
            [democracyworks.kehaar :as k]
            [langohr.consumers :as lc]))

(def messages-from-rabbit (async/chan))

(lc/subscribe a-rabbit-channel
              "watership"
              (k/simple-pass-through messages-from-rabbit)
              subscribe-options)
```

edn-encoded payloads on the "watership" queue will be decoded and
placed on the `messages-from-rabbit` channel for you to deal with as
you like.

### Applying a function to all messges on a RabbitMQ queue and responding on the reply-to queue with a correlation ID.

```clojure
(ns example
  (:require [democracyworks.kehaar :as kehaar]
            [langohr.consumers :as kehaar]))

(defn factorial [n]
  (reduce * 1 (range 1 (inc n))))

(lc/subscribe a-rabbit-channel
              "get-factorial"
              (kehaar/simple-responder factorial)
              {:auto-ack true})
```

edn-encoded payloads on the "get-factorial" queue will be decoded and
passed to the `factorial` function and the result will be encoded as
edn and delivered to the reply-to queue with the correlation ID.

### Using core.async channels to enqueue and receive replies to a RabbitMQ queue

```clojure
(ns example
  (:require [democracyworks.kehaar :as kehaar]
            [clojure.core.async :as async]))

(def factorial-ch (async/chan))
(def request-factorial (kehaar/ch->response-fn factorial-ch))

(kehaar/wire-up-service a-rabbit-channel
                        "get-factorial"
                        factorial-ch)
```

Calling `(request-factorial 5)` will return a core.async channel for
you to listen for the result from the "get-factorial"
queue. `wire-up-service` listens on `factorial-ch` for messages,
creates a response channel, sends a message to the "get-factorial"
queue with a correlation ID, listens on a response queue, and finally
puts the response (edn-decoded, naturally) onto the response channel.

```clojure
(let [response-ch (request-factorial 5)]
  (async/<!! response-ch)) ;;=> 120
```

## License

Copyright © 2015 Democracy Works, Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
