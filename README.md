[![Bintray](https://img.shields.io/bintray/v/vroste/maven/rezilience?label=latest)](https://bintray.com/vroste/maven/rezilience/_latestVersion)

# Rezilience

- [About](#about)
- [Installation](#installation)
- [Circuit Breaker](#circuit-breaker)
  * [Features](#features)
  * [Usage](#usage)
- [Bulkhead](#bulkhead)
  * [Usage](#usage-1)
- [RateLimiter](#ratelimiter)
  * [Usage](#usage-2)
- [Credits](#credits)

## About

`rezilience` is a ZIO-native collection of utilities for making asynchronous systems more resilient to failures.

It is inspired by [Polly](https://github.com/App-vNext/Polly), [Resilience4J](https://github.com/resilience4j/resilience4j) and [Akka](https://doc.akka.io/docs/akka/current/common/circuitbreaker.html).

It currently consists of:

* `CircuitBreaker`
* `Bulkhead`
* `RateLimiter`

and will include in future releases:

* `Retry`: utilities for retrying on failures

## Installation

Add to your build.sbt:

```scala
resolvers += Resolver.jcenterRepo
libraryDependencies += "nl.vroste" %% "rezilience" % "<version>"
```

The latest version is built against ZIO 1.0.1.

## Circuit Breaker
Make calls to an (external) resource through the CircuitBreaker to safeguard the resource against overload. When too many calls have failed, the circuit breaker will trip and calls will fail immediately. This also prevents a queue of calls waiting for response from the resource until timeout.

### Features
* Support for custom error type (the `E` in `ZIO[R, E, A]`) for indicating failures. As with ZIO, you are not limited by `Exception`s for failures. 
* Define which errors are to be considered a failure for the CircuitBreaker to count using a partial function
* Two tripping strategies:
  * Simple: trip the circuit breaker when the _number_ of consecutive failing calls exceeds some threshold.
  * Advanced: trip when the _proportion_ of failing calls exceeds some threshold.
* Exponential backoff for resetting the circuit breaker, or whatever ZIO `Schedule` fits your needs.
* Support for custom tripping strategies via the `TrippingStrategy` trait
* Observe state changes via callback method

### Usage

A `CircuitBreaker` is a Managed resource

```scala
import zio._
import zio.clock.Clock
import zio.duration._
import nl.vroste.rezilience._
import CircuitBreaker._

// We use Throwable as error type in this example 
def myCallToExternalResource(someInput: String): ZIO[Any, Throwable, Int] = ???

val circuitBreaker: ZManaged[Clock, Nothing, CircuitBreaker] = CircuitBreaker.make(
    trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10),
    resetPolicy = Schedule.exponential(1.second),
    onStateChange = (s: State) => ZIO(println(s"State changed to ${s}")).ignore
    )

circuitBreaker.use { cb =>
    val result: ZIO[Any, CircuitBreakerCallError[Throwable], Int] = cb.withCircuitBreaker(myCallToExternalResource("some input"))
}
```

## Bulkhead

`Bulkhead` limits the resources used by some system by limiting the number of concurrent calls to that system. Calls that exceed that number are immediately rejected with a `BulkheadError`. To ensure good utilisation of the system, however, there is a queue/buffer of some size for waiting calls.
 
Using a `Bulkhead` also prevents queueing up of requests, which consume resources in the calling system, by rejecting calls immediately when the queue is full.

Any Bulkhead can execute any type of `ZIO[R, E, A]` effects, so you can execute effects of different types while limiting concurrent usage of the same underlying resource.

A `Bulkhead` is implemented as a `ZManaged`.

### Usage

```scala
import nl.vroste.rezilience.Bulkhead.BulkheadError
import zio._
import nl.vroste.rezilience._

// We use Throwable as error type in this example 
def myCallToExternalResource(someInput: String): ZIO[Any, Throwable, Int] = ???

val bulkhead: UManaged[Bulkhead] = Bulkhead.make(maxInFlightCalls = 10, maxQueueing = 32)

bulkhead.use { bulkhead =>
  val result: ZIO[Any, BulkheadError[Throwable], Int] =
        bulkhead.call(myCallToExternalResource("some input"))
       
}
```

## RateLimiter
`RateLimiter` limits the number of calls to some resource to a maximum number in some interval. It is similar to Bulkhead, but while Bulkhead limits the number of concurrent calls, RateLimiter limits the rate of calls.

RateLimiter is created without type parameters and allows any effect with any environment and error channel to be called under the protection of rate limiting.

### Usage

```scala
import zio._
import zio.duration._
import nl.vroste.rezilience._

// We use Throwable as error type in this example 
def myCallToExternalResource(someInput: String): ZIO[Any, Throwable, Int] = ???

val rateLimiter: UManaged[RateLimiter] = RateLimiter.make(max = 10, interval = 1.second)

rateLimiter.use { rateLimiter =>
  val result: ZIO[Any, Throwable, Int] =
        rateLimiter.call(myCallToExternalResource("some input"))
       
}
```

## Credits
<small><i><a href='http://ecotrust-canada.github.io/markdown-toc/'>Table of contents generated with markdown-toc</a></i></small>
