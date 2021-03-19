---
layout: docs
title: Metrics
permalink: docs/metrics/
---

# Metrics

On the JVM, `rezilience` policies offer metrics for monitoring and alerting purposes, for example when a queue is increasing.

The policies have a method `makeWithMetrics` that allows specifying a 'callback' effect that is executed periodically and provided with a Metrics object. 

Some metric values are recorded as distributions via [HDR Histogram](https://hdrhistogram.github.io/HdrHistogram/), for accurate statistics over a wide range of values. Accessors are provided for mean values.

Two or more metric objects can be combined using the `+` operator to get metrics for the combined interval, with correctly summed histograms.

Finally, during the release of a policy's `ZManaged`, metrics for the final interval are emitted to ensure an accurate total. 

## RateLimiter

| Name         | Type       | Description                         |
|----------------|-----------------------------------------------------|-------------------------------------|
| latency | Histogram | Time between when a task is enqueued and when it is started |
| tasksEnqueued | Int | Total number of tasks enqueued in this interval |
| currentlyEnqueued | Int | Number of tasks currently waiting to be started