[![Bintray](https://img.shields.io/bintray/v/vroste/maven/rezilience?label=latest)](https://bintray.com/vroste/maven/rezilience/_latestVersion)

# Rezilience

- [About](#about)
- [Features / Design goals](#features---design-goals)
- [Installation](#installation)
- [General usage](#general-usage)
- [Circuit Breaker](#circuit-breaker)
  * [Features](#features)
  * [Usage example](#usage-example)
- [Bulkhead](#bulkhead)
  * [Usage example](#usage-example-1)
- [RateLimiter](#ratelimiter)
  * [Usage](#usage)
- [Retry](#retry)
  * [Usage](#usage-1)
- [Combining policies](#combining-policies)
- [Additional resiliency recommendations](#additional-resiliency-recommendations)
- [Credits](#credits)

## About

`rezilience` is a ZIO-native collection of policies for making asynchronous systems more resilient to failures.

It is inspired by [Polly](https://github.com/App-vNext/Polly), [Resilience4J](https://github.com/resilience4j/resilience4j) and [Akka](https://doc.akka.io/docs/akka/current/common/circuitbreaker.html).

It consists of these policies:

* `CircuitBreaker`
* `Bulkhead`
* `RateLimiter`
* `Retry`

## Features / Design goals
* Type-safety: all errors that can result from any of the `rezilience` policies are encoded in the method signatures, so no unexpected RuntimeExceptions.
* Support for your own error types (the `E` in `ZIO[R, E, A]`) instead of requiring your effects to have `Exception` as error type
* Lightweight: `rezilience` uses only ZIO fibers and will not create threads or blocking
* Resource-safe: built on ZIO's `ZManaged`, any allocated resources are cleaned up safely after use. Call interruptions are handled properly.
* Thread-safe: all policies are safe under concurrent use.
* ZIO integration: some policies take for example ZIO `Schedule`s and `rezilience` tries to help type inference using variance annotations
* Metrics: all policies (will) provide usage metrics for monitoring purposes
* Composable: policies can be composed into one overall policy
* Discoverable: no syntax extensions or implicit conversions, just plain scala 

## Installation

Add to your build.sbt:

```scala
resolvers += Resolver.jcenterRepo
libraryDependencies += "nl.vroste" %% "rezilience" % "<version>"
```

The latest version is built against ZIO 1.0.1 and is available for Scala 2.12, 2.13 and Scala.JS 1.2.

## General usage

`rezilience` policies are created as `ZManaged` resources. This allows them to run background operations which are cleaned up safely after usage. Since these `ZManaged`s are just descriptions of the policy, they can be passed around to various call sites and `use`d to create many instances.

All instantiated policies are defined as traits with an `apply` method that takes a ZIO effect as parameter. Therefore a policy can be used as if it were a function taking a ZIO effect, eg:

```scala
Retry.make(...).use { retryPolicy => 
  retryPolicy(callToExternalSystem) // shorthand for retryPolicy.apply(callToExternalSystem) 
}
```

Some policies do not require any type information upon their creation, all types are inferred during usage (calling `apply` like in the example above). Other policies can have behavior that is dependent on the type of error of the effects they are applied on. They can only be applied on effects with an `E` that is a subtype of the errors that the policy is defined for. For example:

```scala
val isFailure: PartialFunction[Error, Boolean] = {
  case MyNotFatalError => false
  case _: Error        => true
}

CircuitBreaker.withMaxFailures(3, isFailure = isFailure).use { circuitBreaker => circuitBreaker(callToExternalSystem) }
```

## Circuit Breaker
Make calls to an external system through the CircuitBreaker to safeguard that system against overload. When too many calls have failed, the circuit breaker will trip and calls will fail immediately, giving the external system some time to recover. This also prevents a queue of calls waiting for response from the external system until timeout.

### Features
* Define which errors are to be considered a failure for the CircuitBreaker to count using a partial function
* Two tripping strategies:
  * Simple: trip the circuit breaker when the _number_ of consecutive failing calls exceeds some threshold.
  * Advanced: trip when the _proportion_ of failing calls exceeds some threshold.
* Exponential backoff for resetting the circuit breaker, or whatever ZIO `Schedule` fits your needs.
* Support for custom tripping strategies implementing the `TrippingStrategy` trait
* Observe state changes via a callback method

### Usage example

```scala
import nl.vroste.rezilience.CircuitBreaker._
import nl.vroste.rezilience._
import zio._
import zio.clock.Clock
import zio.console.putStrLn
import zio.duration._

object CircuitBreakerExample {
  // We use Throwable as error type in this example
  def callExternalSystem(someInput: String): ZIO[Any, Throwable, Int] = ZIO.succeed(someInput.length)

  val circuitBreaker: ZManaged[Clock, Nothing, CircuitBreaker[Any]] = CircuitBreaker.make(
    trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10),
    resetPolicy = Schedule.exponential(1.second),
    onStateChange = (s: State) => ZIO(println(s"State changed to ${s}")).ignore
  )

  circuitBreaker.use { cb =>
    val result: ZIO[Any, CircuitBreakerCallError[Throwable], Int] = cb(callExternalSystem("some input"))

    result
      .flatMap(r => putStrLn(s"External system returned $r"))
      .catchSome {
        case CircuitBreakerOpen =>
          putStrLn("Circuit breaker blocked the call to our external system")
        case WrappedError(e)    =>
          putStrLn(s"External system threw an exception: $e")
      }
  }
}
```

## Bulkhead

`Bulkhead` limits the number of concurrent calls to a system. Calls exceeding this number are queued, this helps to maximize resource usage. When the queue is full, calls are immediately rejected with a `BulkheadRejection`. 
 
Using a `Bulkhead` not only protects the external system, it also prevents queueing up of requests, which consumes resources in the calling system, by rejecting calls immediately when the queue is full.

Any Bulkhead can execute any type of `ZIO[R, E, A]`, so you can execute effects of different types while limiting concurrent usage of the same underlying resource.

### Usage example

```scala
import zio._
import nl.vroste.rezilience._
import nl.vroste.rezilience.Bulkhead.BulkheadError

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
ZIO already has excellent built-in support for retrying effects on failures using a `Schedule`, there is not much this library can add.

Two helper methods are made available:

* `Retry.exponentialBackoff`  
  Exponential backoff with a maximum delay and an optional maximum number of recurs. When the maximum delay is reached, subsequent delays are the maximum. 
  
* `Retry.whenCase`  
  Accepts a partial function and a schedule and will apply the schedule only when the input matches partial function. This is useful to retry only on certain types of failures/exceptions
  
For consistency with the other policies and to support combining policies, there is `Retry.make(schedule)`.
  
### Usage
  
```scala
import zio._
import zio.duration._
import nl.vroste.rezilience._
import java.util.concurrent.TimeoutException

val myEffect: ZIO[Any, Exception, Unit] = ???

// Retry exponentially on timeout exceptions
myEffect.retry(
  Retry.Schedule.whenCase({ case TimeoutException => })(Retry.Schedule.exponentialBackoff(min = 1.second, max = 1.minute))
)
```

## Combining policies

The above policies can be combined into one `Policy` to combine several resilience strategies.

Many variations of policy combinations are possible, but one example is to have a `Retry` around a `RateLimiter`.

To compose policies, convert them into a `Policy` instance using `toPolicy` and use `.compose` to wrap it in another policy. For example:

```scala
val policy: ZManaged[Clock, Nothing, Policy[Any, Any]] = for {
  rateLimiter <- RateLimiter.make(1, 2.seconds)
  bulkhead    <- Bulkhead.make(2)
  retry       <- Retry.make(Schedule.recurs(3))
} yield bulkhead
  .toPolicy[Any] compose rateLimiter.toPolicy[Any] compose retry.toPolicy[Any]
```

Unfortunately the type inference here is not quite there yet, requiring the `Any` type parameters. 

Because of type-safety, you sometimes need to transform your individual policies to work with the errors produced by inner policies. Take for example, a Retry around a `CircuitBreaker` that you want to call with. If you want to retry on any error, a `Retry[Any]` is fine. But if you only want to use a Retry on ZIOs with error type `E` and your Retry policy defines that it only wants to retry a subset of those errors, eg `E1 <: E`, you will need to adapt it to decide what to do with the `CircuitBreakerError[E]` that is the output error type of the `CircuitBreaker`. 

## Additional resiliency recommendations
The following additional resiliency policies are not included in this library. Some because they are standard ZIO functionality. They can be applied in combination with `rezilience` policies.

* Add a timeout to calls to external systems using eg `ZIO#timeout`, `timeoutFail` or `timeoutTo`. When combining different policies from this library, the timeout should be the first decorator.

* Add a cache to speed up response time and provide an alternative in case of failures. `rezilience` does not provide a cache since it is a specialized topic. A library like [scalacache](https://cb372.github.io/scalacache/docs/modes.html) offers ZIO integration via cats-effect interop.

* Add a fallback using `ZIO#orElse`, a 'degraded mode' alternative response when a resource is not available. You usually want to do this as the outermost decorator.

## Credits
<small><i><a href='http://ecotrust-canada.github.io/markdown-toc/'>Table of contents generated with markdown-toc</a></i></small>
