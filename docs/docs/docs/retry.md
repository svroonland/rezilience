---
layout: docs
title: Retry
permalink: docs/retry/
---

# Retry
ZIO already has excellent built-in support for retrying effects on failures using a `Schedule`, there is not much this library can add.

Two helper methods are made available:

* `Retry.exponentialBackoff`  
  Exponential backoff with a maximum delay and an optional maximum number of recurs. When the maximum delay is reached, subsequent delays are the maximum. 
  
* `Retry.whenCase`  
  Accepts a partial function and a schedule and will apply the schedule only when the input matches partial function. This is useful to retry only on certain types of failures/exceptions
  
For consistency with the other policies and to support combining policies, there is `Retry.make(schedule)`.
  
## Usage
  
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
