# democracyworks.kehaar

A Clojure library designed to pass messages between RabbitMQ and core.async.

## Usage

```clojure
(ns example
  (:require [core.async :as async]
            [democracyworks.kehaar :as k]
            [langohr.consumers :as lc]))

(def messages-from-rabbit (async/chan))

(lc/subscribe a-rabbit-channel
              "watership"
              (simple-pass-through messages-from-rabbit)
              subscribe-options)
```

edn-encoded payloads on the "watership" queue will be decoded and
placed on the `messages-from-rabbit` channel for you to deal with as
you like.

Functions for pulling off async channels to publish to RabbitMQ to
come.

## License

Copyright © 2015 Democracy Works, Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
