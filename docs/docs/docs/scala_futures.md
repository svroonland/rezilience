---
layout: docs
title: Scala Futures
permalink: docs/scala_futures/
---

# Scala Futures

`rezilience` is built on top of [ZIO](https://www.zio.dev) but a Scala `Future` based interface is available as well in the `rezilience-future` library. Most functionality of the library is available with a more familiar interface for apps built using standard Scala library `Future`s.

To install, add the following to your build.sbt:

```scala
resolvers += Resolver.jcenterRepo
libraryDependencies += "nl.vroste" %% "rezilience-future" % "<version>"
```

## Retry


```scala mdoc:silent
import nl.vroste.rezilience.future.Retry
import scala.concurrent.duration._
import scala.concurrent.Future

// Get this from your framework, eg Play or Akka
import scala.concurrent.ExecutionContext.Implicits.global

val myCall: Future[Int] = Future.successful(3)

val retryPolicy: Retry = Retry.make(min = 1.second, max = 10.seconds)

val result: Future[Int] = retryPolicy(myCall)
```



