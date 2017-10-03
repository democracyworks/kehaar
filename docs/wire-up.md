# kehaar.wire-up

```clojure
(require '[kehaar.wire-up :as wire-up])
```

Some typical patterns:

* You want to listen for events on the "events" exchange. So you'll
  need to declare it first.

```clojure
(let [ch (wire-up/declare-events-exchange conn
                                          "events"
                                          "topic"
                                          (config [:topics "events"]))]
  ;; later, on exit, close ch
  (rmq/close ch))
```

* You want to connect to an external query-response service over
  RabbitMQ.

```clojure
(let [ch (wire-up/external-service conn
                                   "service-works.service.process"
                                   process-channel)] ;; a core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

Then you can create a function that "calls" that service, like so:

```clojure
(def process (wire-up/async->fn process-channel))
```

The returned function, when called, will return a core.async channel
that will eventually contain the result. If you're chaining up
services, this channel can be returned from a handler as well, letting
you chain async calls. The returned function takes a single argument,
which must be some edenizable value (including `nil`).

Some notes:

There is also `wire-up/external-service-fire-and-forget` and
`wire-up/async->fire-and-forget-fn` which are for services where you
do not wish to wait for an answer. Instead of returning a core.async
channel for a response, functions created with
`wire-up/async->fire-and-forget-fn` will simply return `true` when
they have successfully placed the message on the outgoing channel.

* You want to make a query-response service. Send requests to
  in-channel and get responses on out-channel (core.async channels).


```clojure
(let [ch (wire-up/incoming-service conn
                                   "service-works.service.process"
                                   (config [:queues "service-works.service.process"])
                                   in-channel
                                   out-channel)]
  ;; later, on exit, close ch
  (rmq/close ch))
```

Later, you can add a handler to it like this:

```clojure
(wire-up/start-responder! in-channel out-channel handler-function)
```

* You want to connect to an external query-response service over
  RabbitMQ that streams multiple responses to a single request.

```clojure
(let [ch (wire-up/streaming-external-service
           conn
           "service-works.service.query"
           query-channel)] ;; a core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

Then you can create a function that "calls" that service, like so:

```clojure
(def query (wire-up/async->fn query-channel))
```

The returned function, when called, will return a core.async channel
that will eventually contain the results. The returned function takes
a single argument, which must be some edenizable value (including
`nil`).

* You want to make a streaming query-response service. Send requests
  to in-channel and get responses on out-channel (core.async
  channels).

```clojure
(let [ch (wire-up/incoming-service conn
                                   "service-works.service.process"
                                   (config [:queues "service-works.service.process"])
                                   in-channel
                                   out-channel)]
  ;; later, on exit, close ch
  (rmq/close ch))
```

Later, you can add a handler to it like this:

```clojure
(wire-up/start-streaming-responder!
 conn in-channel out-channel streaming-handler-function 10)
```

Some notes:

`handler-function` should be a function of a single argument. It
accepts messages from the queue, which had been serialized and
deserialized as EDN. So expect any kind of serializable value,
including `nil`.

`streaming-handler-function` should be a function of a single
argument, like `handler-function`, but should always return a sequence
(lazy, if you like) or a core.async channel. Each value in the
sequence or on the channel will be returned to the client in
order. When returning a core.async channel, you must close the channel
when there are no more values.

`wire-up/start-responder!` and `wire-up/start-streaming-responder!`
all take an extra optional argument for the number of threads to take
and handle messages on. The default is 10.

Incoming messages are nacked if the thread is taking too long to
process the messages. This allows different instances of the service
to process those messages.

`in-channel` should be an unbuffered channel. `out-channel` should
have a large buffer to get messages out to RabbitMQ as soon as
possible.

`wire-up/incoming-service` takes an optional argument
`ignore-no-reply-to`. If true, kehaar will not log when an incoming
message is missing the `:reply-to` metadata key. This will help
prevent noise in the logs if the request comes from
`async->fire-and-forget-fn`.

`wire-up/incoming-service` takes a further optional argument
`prefetch-limit`. If it's non-nil, the channel's prefetch limit will
be set to its value.

* You want to listen for events on the events exchange. (First declare
  the exchange above, only do that once.)

```clojure
(let [ch (wire-up/incoming-events-channel conn
                                          "my-service.events.create-something"
                                          (config [:queues "my-service.events.create-something"])
                                          "events"
                                          "create-something"
                                          create-something-events ;; events core.async channel
                                          100)] ;; timeout
  ;; later, on exit, close ch
  (rmq/close ch))
```

Later, you can add an event handler like this:

```
(wire-up/start-event-handler! in-channel handler-function)
```

Some notes:

`in-channel` should be unbuffered.

`handler-function` is a function of exactly one argument, which is the
message that was passed down the rabbit hole. It's serialized on the
way to a ByteString using EDN, so expect some data that can be
edenized. The return can be any edenizable value (including `nil`) OR
a core.async channel that will include the result (also must be
edenizable). That second option lets you maintain asynchrony because
other services using kehaar are doing the same.

`wire-up/start-event-handler!` takes an optional argument for the
number of threads to take and handle messages on. The default is 10.

Incoming messages are nacked if the thread is taking too long to
process the messages. This allows different instances of the service
to process those messages.

`wire-up/incoming-events-channel` takes a further optional argument
`prefetch-limit`. If it's non-nil, the channel's prefetch limit will
be set to its value.

* You want to send events on the events exchange. (First declare the
  exchange above, only do that once.)

```clojure
(let [ch (wire-up/outgoing-events-channel conn
                                          "events"
                                          "create-something"
                                          create-something-events)] ;; events core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

The event messages you send on the channel should be edenizable.
