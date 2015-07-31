# kehaar-example

An example project that demonstrates the use of Kehaar.

The project provides a service (calculating Fibonacci numbers) over
RabbitMQ, calls that service over RabbitMQ, sends messages to a topic
when it calcultes Fibonacci numbers, and listens on that topic and
reacts to it.

## Usage

This project assumes a RabbitMQ server running locally on its standard
port.

To run the project: `lein run`.

It will make 500 requests to the Fibonacci service, logging requests,
responses and events received.

See the core.clj for details.
