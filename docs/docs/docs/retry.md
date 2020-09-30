---
layout: docs
title: Retry
permalink: docs/retry/
---

# Retry

`Retry` is a policy that retries effects on failure

`rezilience` offers some convenience methods for retrying with a common-practice strategy via `Retry.make`:

* By default the first retry is done immediately. With transient / random failures this method gives the highest chance of fast success.
* After that Retry uses exponential backoff between some minimum and maximum duration. 
* Jitter is added to prevent spikes of retries.
* An optional maximum number of retries ensures that retrying does not continue forever.

ZIO already has excellent built-in support for retrying effects on failures using a `Schedule` and `rezilience` is built on top of that. Retry can accept any `ZIO` [`Schedule`](https://zio.dev/docs/datatypes/datatypes_schedule).

For further Schedule composition, the above strategy is also available as a `ZIO` `Schedule` along with some other building blocks in `Retry.Schedules`:

* `Retry.Schedules.common`  
  The strategy with immediate retry, exponential backoff and jitter as outlined above.

* `Retry.Schedules.exponentialBackoff`  
  Exponential backoff with a maximum delay and an optional maximum number of recurs. When the maximum delay is reached, subsequent delays are the maximum. 
  
* `Retry.Schedules.whenCase`  
  Accepts a partial function and a schedule and will apply the schedule only when the input matches partial function. This is useful to retry only on certain types of failures/exceptions.
  
## Usage
  
```scala mdoc
import zio._
import zio.duration._
import nl.vroste.rezilience._
import java.util.concurrent.TimeoutException

val myEffect: ZIO[Any, Exception, Unit] = ???

// Create a Retry policy that only retries timeouts
val retry = Retry.make(
  Retry.Schedules.whenCase({ case TimeoutException => }) {
    Retry.Schedules.common(min = 1.second, max = 1.minute)
  }
)

retry.use { retryPolicy => 
  retryPolicy(myEffect)
}
```

## Different retry strategies for different errors

By composing ZIO `Schedule`s, you can define different retries for different types of errors:

```scala 

val retry = Retry.make(
  Retry.Schedules.whenCase({ case TimeoutException => }) {
    Retry.Schedules.common(min = 1.second, max = 1.minute)
  } || Retry.Schedules.whenCase({ case UnknownHostException => }) {
    Retry.Schedules.common(min = 1.day, max = 5.days)
  }
)

```