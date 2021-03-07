---
layout: docs
title: Rezilience
permalink: docs/
---

# Rezilience


`rezilience` is a ZIO-native collection of policies for making asynchronous systems more resilient to failures, inspired by [Polly](https://github.com/App-vNext/Polly), [Resilience4J](https://github.com/resilience4j/resilience4j) and [Akka](https://doc.akka.io/docs/akka/current/common/circuitbreaker.html).

It consists of these policies:

| Policy         | Reactive/Proactive | Description                                              |
|----------------|--------------------|----------------------------------------------------------|
| [CircuitBreaker](circuitbreaker) | Reactive           | Temporarily prevent trying calls after too many failures |
| [RateLimiter](ratelimiter)    | Proactive          | Limit the rate of calls to a system                      |
| [Bulkhead](bulkhead)       | Proactive          | Limit the number of in-flight calls to a system          |
| [Retry](retry)          | Reactive           | Try again after transient failures                       |
| [Timeout](timeout)        | Reactive           | Interrupt execution if a call does not complete in time  | 

## Features / Design goals
* Type-safety: all errors that can result from any of the `rezilience` policies are encoded in the method signatures, so no unexpected RuntimeExceptions.
* Support for your own error types (the `E` in `ZIO[R, E, A]`) instead of requiring your effects to have `Exception` as error type
* Lightweight: `rezilience` uses only ZIO fibers and will not create threads or block
* Resource-safe: built on ZIO's `ZManaged`, any allocated resources are cleaned up safely after use. Call interruptions are handled properly.
* Thread-safe: all policies are safe under concurrent use.
* ZIO integration: some policies take for example ZIO `Schedule`s and `rezilience` tries to help type inference using variance annotations
* Metrics: all policies (will) provide usage metrics for monitoring purposes
* Composable: policies can be composed into one overall policy
* Discoverable: no syntax extensions or implicit conversions, just plain scala 

## Installation

[![Sonatype Nexus (Releases)](https://img.shields.io/nexus/r/nl.vroste/rezilience_2.13?nexusVersion=3&server=https%3A%2F%2Fnexus.pentaho.org)](https://repo1.maven.org/maven2/nl/vroste/rezilience_2.13/)

[![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/nl.vroste/rezilience_2.13?server=https%3A%2F%2Foss.sonatype.org)](https://oss.sonatype.org/content/repositories/snapshots/nl/vroste/rezilience_2.13/)

Add to your build.sbt:

```scala
libraryDependencies += "nl.vroste" %% "rezilience" % "<version>"
```

The latest version is built against ZIO 1.0.4-2 and is available for Scala 2.12, 2.13 and Scala.JS 1.5.

## Usage example

Limit the rate of calls:

```scala
import zio._
import zio.duration._
import nl.vroste.rezilience._

def myCallToExternalResource(someInput: String): ZIO[Any, Throwable, Int] = ???

val rateLimiter: UManaged[RateLimiter] = RateLimiter.make(max = 10, interval = 1.second)

rateLimiter.use { rateLimiter =>
  val result: ZIO[Any, Throwable, Int] =
        rateLimiter(myCallToExternalResource("some input"))
       
}
```

