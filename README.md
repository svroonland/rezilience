[![Bintray](https://img.shields.io/bintray/v/vroste/maven/rezilience?label=latest)](https://bintray.com/vroste/maven/rezilience/_latestVersion)

# Rezilience

- [About](#about)
- [Benefits over other libraries](#benefits-over-other-libraries)
- [Installation](#installation)
- [Circuit Breaker](#circuit-breaker)
  * [Features](#features)
  * [Usage](#usage)
- [Bulkhead](#bulkhead)
  * [Usage](#usage-1)
- [RateLimiter](#ratelimiter)
  * [Usage](#usage-2)
- [Retry](#retry)
  * [Usage](#usage-3)
- [Combining policies](#combining-policies)
  * [Usage](#usage-4)
- [Credits](#credits)
## About

`rezilience` is a ZIO-native collection of utilities for making asynchronous systems more resilient to failures.

It is inspired by [Polly](https://github.com/App-vNext/Polly), [Resilience4J](https://github.com/resilience4j/resilience4j) and [Akka](https://doc.akka.io/docs/akka/current/common/circuitbreaker.html).

It consists of:

* `CircuitBreaker`
* `Bulkhead`
* `RateLimiter`
* `Retry`

## Benefits over other libraries
* `rezilience` allows you to use your own error type (the `E` in `ZIO[R, E, A]`) instead of forcing your effects to have `Exception` as error type
* `rezilience` is lightweight, using only ZIO fibers and not spawning threads or blocking
* It integrates smoothly with ZIO and ZIO libraries without prescribing any constraints and with type inference (as much as possible)

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

val circuitBreaker: ZManaged[Clock, Nothing, CircuitBreaker[Any]] = CircuitBreaker.make(
    trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10),
    resetPolicy = Schedule.exponential(1.second),
    onStateChange = (s: State) => ZIO(println(s"State changed to ${s}")).ignore
    )

circuitBreaker.use { cb =>
    val result: ZIO[Any, CircuitBreakerCallError[Throwable], Int] = cb(myCallToExternalResource("some input"))
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
        bulkhead(myCallToExternalResource("some input"))
       
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
        rateLimiter(myCallToExternalResource("some input"))
       
}
```

## Retry
ZIO contains excellent built-in support for retrying effects on failures using `Schedule`, there is not much this library could add.

Two helper methods are made available:

* `Retry.exponentialBackoff`  
  Exponential backoff with a maximum delay and an optional maximum number of recurs. When the maximum delay is reached, subsequent delays are the maximum. 
  
* `Retry.whenCase`  
  Accepts a partial function and a schedule and will apply the schedule only when the input matches partial function. This is useful to retry only on certain types of failures/exceptions
  
### Usage
  
```scala
import zio._
import zio.duration._
import nl.vroste.rezilience._
import java.util.concurrent.TimeoutException

val myEffect: ZIO[Any, Exception, Unit] = ???

// Retry exponentially on timeout exceptions
myEffect.retry(
  Retry.make(Retry.Schedule.whenCase({ case TimeoutException => })(Retry.Schedule.exponentialBackoff(min = 1.second, max = 1.minute)))
)
```

## Combining policies

The above policies can be combined into one `Policy` to combine several resilience strategies.

Many variations of policy combinations are possible, but one example is to have a `Retry` around a `RateLimiter`.

Because of type-safety, you sometimes need to transform your individual policies to work with the errors produced by inner policies. Take for example, a Retry around a `CircuitBreaker` that you want to call with. If you want to retry on any error, a `Retry[Any]` is fine. But if you only want to use a Retry on ZIOs with error type `E` and your Retry policy defines that it only wants to retry a subset of those errors, eg `E1 <: E`, you will need to adapt it to decide what to do with the `CircuitBreakerError[E]` that is the output error type of the `CircuitBreaker`. For example:


TODO



`PolicyWrap` can combine the above policies into one wrapper interface.

### Usage

```scala
import zio._
import zio.clock._
import zio.duration._
import nl.vroste.rezilience._

val policy: ZManaged[Clock, Nothing, PolicyWrap[Any]] = ZManaged.mapN(
  RateLimiter.make(1, 1.second),
  Bulkhead.make(10),
  CircuitBreaker.withMaxFailures(10)
)(PolicyWrap.make(_, _, _))

val myEffect: ZIO[Any, Exception, Unit] = ???

policy.use { policy => 
  policy(myEffect)
}

```

## Additional resiliency recommendations
These additional resiliency policies are standard ZIO functionality and therefore have no dedicated implementations in this library, but they can be applied in combination with `rezilience` policies.

* Add a timeout to calls to external systems using eg `ZIO#timeout`, `timeoutFail` or `timeoutTo`. When combining different policies from this library, the timeout should be the first decorator.

* Add a fallback using `ZIO#orElse`, a 'degraded mode' alternative response when a resource is not available.

## Credits
<small><i><a href='http://ecotrust-canada.github.io/markdown-toc/'>Table of contents generated with markdown-toc</a></i></small>
