# Rezilience

`rezilience` is a collection of policies for making asynchronous systems more resilient to failures, built for ZIO and Scala Futures.

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

## Documentation
[Documentation](https://www.vroste.nl/rezilience)

## Installation

[![Bintray](https://img.shields.io/bintray/v/vroste/maven/rezilience?label=latest)](https://bintray.com/vroste/maven/rezilience/_latestVersion)

Add to your `build.sbt`:

```scala
resolvers += Resolver.jcenterRepo
libraryDependencies += "nl.vroste" %% "rezilience" % "<version>"
```

or for the Scala Futures interface:

```scala
resolvers += Resolver.jcenterRepo
libraryDependencies += "nl.vroste" %% "rezilience-future" % "<version>"
```