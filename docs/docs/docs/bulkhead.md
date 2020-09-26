---
layout: docs
title: Bulkhead
permalink: docs/bulkhead/
---

# Bulkhead

`Bulkhead` limits the number of concurrent calls to a system. Calls exceeding this number are queued, this helps to maximize resource usage. When the queue is full, calls are immediately rejected with a `BulkheadRejection`. 
 
Using a `Bulkhead` not only protects the external system, it also prevents queueing up of requests, which consumes resources in the calling system, by rejecting calls immediately when the queue is full.

Any Bulkhead can execute any type of `ZIO[R, E, A]`, so you can execute effects of different types while limiting concurrent usage of the same underlying resource.

## Usage example

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
