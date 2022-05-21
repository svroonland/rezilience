---
layout: docs
title: Metrics
permalink: docs/metrics/
---

# Metrics

`rezilience` policies offer metrics for debugging, monitoring and alerting purposes. Metrics can provide insight whether the chosen parameters are sufficient for the usage of the policy (or vice versa), for example to see if a Bulkhead queue is filling up over time.

Metrics are available for `CircuitBreaker`, `Bulkhead` and `RateLimiter` (the latter two only for the JVM).

The policies have a method `addMetrics` that allows specifying a 'callback' effect that is executed periodically and provided with a Metrics object. 

Some metric values are recorded as distributions via [HDR Histogram](https://hdrhistogram.github.io/HdrHistogram/), for accurate statistics over a wide range of values. Accessors are provided for mean values.

Two or more metric objects can be combined using the `+` operator to get metrics for the combined interval, with correctly summed histograms.

Finally, during the release of a policy's `ZManaged`, metrics for the final interval are emitted to ensure an accurate total. 

## Example usage

```scala mdoc:silent
import nl.vroste.rezilience._
import zio._
import zio.duration._
import zio.console._
import zio.clock.Clock

def onMetrics(metrics: BulkheadMetrics): ZIO[Console, Nothing, Any] = {
    console.putStrLn(metrics.toString)
}

def callExternalSystem = ZIO.unit

val bulkhead: ZManaged[Clock, Nothing, Bulkhead] = for {
    innerBulkhead <- Bulkhead.make(maxInFlightCalls = 10)
    bulkhead <- Bulkhead.addMetrics(innerBulkhead, onMetrics, metricsInterval = 10.seconds)
} yield bulkhead

val program: ZIO[Clock, Nothing, Any] = bulkhead.use { bulkhead =>
    bulkhead(callExternalSystem)
}

```

## Available metrics

### Circuit Breaker

| Name         | Type       | Description                         |
|----------------|-----------------------------------------------------|-------------------------------------|
| failedCalls | Long | Number of calls that failed |
| succeededCalls | Int | Number of calls that succeeded |
| rejectedCalls | Int | Number of calls that were rejected |
| stateChanges | Chunk[StateChange] | All state changes |
| lastResetTime | Option[Instant] | Time of the last reset to the Closed state |

### Bulkhead

| Name         | Type       | Description                         |
|----------------|-----------------------------------------------------|-------------------------------------|
| inFlight | Histogram | Distribution of number of calls in flight |
| enqueued | Histogram | Distribution of number of calls that are enqueued |
| latency | Histogram | Time between when a task is enqueued and when it is started |
| currentlyInFlight | Long | Number of tasks that are currently being executed |
| currentlyEnqueued | Long | Number of tasks that are currently being executed |
| tasksStarted | Long | Number of tasks that were started |

### RateLimiter

| Name         | Type       | Description                         |
|----------------|-----------------------------------------------------|-------------------------------------|
| latency | Histogram | Time between when a task is enqueued and when it is started |
| tasksEnqueued | Int | Total number of tasks enqueued in this interval |
| currentlyEnqueued | Int | Number of tasks currently enqueued |