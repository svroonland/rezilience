---
layout: docs
title: Retry
permalink: docs/retry/
---

# Retry

`Retry` is a policy that retries effects on failure

## Common retry strategy

`Retry` implements a common-practice strategy for retrying:

* The first retry is performed immediately. With transient failures this method gives the highest chance of fast success.
* After that, Retry uses an exponential backoff capped to a maximum duration.
* Some random jitter is added to prevent spikes of retries from many call sites applying the same retry strategy.
* An optional maximum number of retries ensures that retrying does not continue forever.

See also https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/

## Usage example

```scala mdoc:silent
import zio._
import zio.duration._
import zio.clock.Clock
import zio.random.Random
import nl.vroste.rezilience._

val myEffect: ZIO[Any, Exception, Unit] = ZIO.unit

val retry: ZIO[Scope with Clock with Random, Nothing, Retry[Any]] = Retry.make(min = 1.second, max = 10.seconds)

ZIO.scoped {
  retry.flatMap { retryPolicy => 
    retryPolicy(myEffect)
  }
}
```

## Custom retry strategy
ZIO already has excellent built-in support for retrying effects on failures using a `Schedule` and `rezilience` is built on top of that. Retry can accept any `ZIO` [`Schedule`](https://zio.dev/docs/datatypes/datatypes_schedule).

Some Schedule building blocks are available in `Retry.Schedules`:

* `Retry.Schedules.common(min: Duration, max: Duration, factor: Double, retryImmediately: Boolean, maxRetries: Option[Int])`  
  The strategy with immediate retry, exponential backoff and jitter as outlined above.

* `Retry.Schedules.exponentialBackoff(min: Duration, max: Duration, factor: Double = 2.0)`  
  Exponential backoff with a maximum delay and an optional maximum number of recurs. When the maximum delay is reached, subsequent delays are the maximum. 
  
* `Retry.Schedules.whenCase[Env, In, Out](pf: PartialFunction[In, Any])(schedule: Schedule[Env, In, Out])`  
  Accepts a partial function and a schedule and will apply the schedule only when the input matches partial function. This is useful to retry only on certain types of failures/exceptions.

## Different retry strategies for different errors

By composing ZIO `Schedule`s, you can define different retries for different types of errors:

```scala mdoc:silent
import java.util.concurrent.TimeoutException
import java.net.UnknownHostException

val isTimeout: PartialFunction[Exception, Any] = {
  case _ : TimeoutException => 
}

val isUnknownHostException: PartialFunction[Exception, Any] = {
  case _ : UnknownHostException => 
}

val retry2 = Retry.make(
  Retry.Schedules.whenCase(isTimeout) { Retry.Schedules.common(min = 1.second, max = 1.minute) } || 
    Retry.Schedules.whenCase(isUnknownHostException) { Retry.Schedules.common(min = 1.day, max = 5.days) }
)

ZIO.scoped {
  retry2.flatMap { retryPolicy => 
    retryPolicy(myEffect)
  }
}
```
