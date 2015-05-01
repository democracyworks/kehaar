# Change Log

## Changes between Kehaar 0.1.0 and 0.x

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

### kehaar/wire-up-service

`kehaar/wire-up-service` no longer declares the queue it operates on
either. **This is a breaking cahnge.** Queues must now already be
delcared. Additionally, internally, it uses a server-named, exclusive,
auto-deleted queue.

### Tests using RabbitMQ

There are now tests which use RabbitMQ, however they are not run by
default with `lein run`. In order to run the RabbitMQ tests, start
`rabbitmq-server` with its default configuration and run `lein test
:rabbit-mq`. To run all tests, run `lein test :all`.

Travis CI has been updated to run those tests as well.
