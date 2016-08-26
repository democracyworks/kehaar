# kehaar.core

## Passing messages from RabbitMQ to core.async

```clojure
(ns example
  (:require [core.async :as async]
            [kehaar.core :as k]))

(def messages-from-rabbit (async/chan))

(k/rabbit=>async a-rabbit-channel
                 "watership"
                 messages-from-rabbit)
```

edn-encoded payloads on the "watership" queue will be decoded and
placed on the `messages-from-rabbit` channel for you to deal with as
you like. Each message has `:message` and `:metadata`.

## Passing messages from core.async to RabbitMQ

```clojure
(ns example
  (:require [core.async :as async]
            [kehaar.core :as k]))

(def outgoing-messages (async/chan))

(k/async=>rabbit outgoing-messages
                 a-rabbit-channel
                 "updates")
```

All messages sent to the `outgoing-messages` channel will encoded as
edn and placed on the "updates" queue. Each message should have
`:message` and `:metadata`.

## Passing messages from core.async to RabbitMQ based on reply-to

```clojure
(ns example
  (:require [core.async :as async]
            [kehaar.core :as k]))

(def outgoing-messages (async/chan))

(k/async=>rabbit-with-reply-to outgoing-messages
                               a-rabbit-channel)
```

All messages sent to the `outgoing-messages` channel will ebe ncoded
as edn and placed on the queue specified in the `:reply-to` key in the
metadata. Each message should have `:message` and `:metadata`.
