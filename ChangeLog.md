# Change Log

## Changes between Kehaar 0.4.0 and HEAD

Added `kehaar.rabbitmq/connect-with-retries` function to make connecting to
RabbitMQ brokers more robust.

## Changes between Kehaar 0.3.0 and 0.4.0

### kehaar.core/ch->response-fn

In 0.2.0 `ch->response-fn` started returning promises instead of core.async
channels. That is now reverted back to core.async channels.
**This is a breaking change.**

### kehaar.wire-up

The `kehaar.wire-up` namespace contains a higher-level interface for
declaring and setting up queues.

### Updated Clojure dependency to 1.7.0.

It came out.

## Changes between Kehaar 0.2.1 and 0.3.0

### Add `kehaar.core` namespace

Since single-level namespaces are not recommended in Clojure, we have
moved the code that was in `kehaar` to a new `kehaar.core` ns.
**This is a breaking change.** You will need to update all your
`(ns ... (:require [kehaar]))` forms in your code to look more like
this instead: `(ns ... (:require [kehaar.core]))`.

### Ack-on-take in `rabbit->async`

Previously kehaar auto-acked every incoming RabbitMQ message. Now it
acks only when something successfully consumes the message from the
core.async channel that it is forwarded to.

### Logging

There are now debug-level log messages when Kehaar consumes a RabbitMQ
message and when it forwards them on to core.async channels.

There are also warn-level log messages when it tries to take from a
closed core.async channel.

This adds a dependency on `clojure.tools.logging` 0.3.1.

### Updated dependencies

Use Clojure 1.7.0-RC2

## Changes between Kehaar 0.2.0 and 0.2.1

### `nil?` checks when taking values from async channels

Pulling from a core.async channel will return `nil` if the channel is
closed, so now we check for that and stop trying to handle those
messages and stop trying to pull more.

### Updated dependencies

Use Clojure 1.7.0-beta3 and Langohr 3.2.0.

## Changes between Kehaar 0.1.0 and 0.2.0

### kehaar/async->rabbit

`kehaar/async->rabbit` no longer declares the queue it operates
on. **This is a breaking change.** Queues must now be already
declared. This allows, for example, the queue to be
[a server-named, exclusive, auto-deleted queue](http://clojurerabbitmq.info/articles/queues.html#declaring-a-temporary-exclusive-queue).

### kehaar/rabbit->async

The old `kehaar/rabbit->async` function has been renamed
`kehaar/rabbit->async-handler-fn`, and `kehaar/rabbit->async` now
takes the RabbitMQ queue and the async channel, handling the
subscription for you. **This is a breaking change.**

`rabbit->async` and `async->rabbit` now appropriately mirror the
other.

### kehaar/rabbit->async-handler-fn

`kehaar/rabbit->async-handler-fn` now blocks if the async channel's
buffer is full, providing the opportunity for some back pressure.

### kehaar/ch->response-fn

`kehaar/ch->response-fn` now returns promises instead of async
channels for the caller to wait on. **This is a breaking change.**

### kehaar/wire-up-service

`kehaar/wire-up-service` no longer declares the queue it operates on
either. **This is a breaking change.** Queues must now already be
delcared. Additionally, internally, it uses a server-named, exclusive,
auto-deleted queue.

### kehaar/responder

`kehaar/simple-responder` has been renamed `kehaar/fn->handler-fn` and
a new function `kehaar/responder` has been made which takes a RabbitMQ
channel and queue and a function to apply to all messages, replying on
the reply-to queue with the result. **This is a breaking change.**

### Tests using RabbitMQ

There are now tests which use RabbitMQ, however they are not run by
default with `lein run`. In order to run the RabbitMQ tests, start
`rabbitmq-server` with its default configuration and run `lein test
:rabbit-mq`. To run all tests, run `lein test :all`.

Travis CI has been updated to run those tests as well.
