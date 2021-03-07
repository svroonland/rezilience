# Rezilience

`rezilience` is a ZIO-native collection of policies for making asynchronous systems more resilient to failures.

It is inspired by [Polly](https://github.com/App-vNext/Polly), [Resilience4J](https://github.com/resilience4j/resilience4j) and [Akka](https://doc.akka.io/docs/akka/current/common/circuitbreaker.html).

It consists of these policies:

* `CircuitBreaker`
* `Bulkhead`
* `RateLimiter`
* `Retry`
* `Timeout`

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

## Documentation
[Documentation](https://svroonland.github.io/rezilience)

## Installation

![Sonatype Nexus (Releases)](https://img.shields.io/nexus/r/nl.vroste/rezilience_2.13?nexusVersion=3&server=https%3A%2F%2Fnexus.pentaho.org)

[![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/nl.vroste/rezilience_2.13?server=https%3A%2F%2Foss.sonatype.org)](https://oss.sonatype.org/content/repositories/snapshots/nl/vroste/rezilience_2.13/)

Add to your build.sbt:

```scala
libraryDependencies += "nl.vroste" %% "rezilience" % "<version>"
```

## Used by

If you are using this library and find it useful, please consider adding your company or project to the list below!
