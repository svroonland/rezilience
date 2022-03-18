---
layout: docs
title: Timeout
permalink: docs/timeout/
---

# Timeout

`Timeout` is a policy that interrupts execution of an effect when it does not complete in time. It is a simple wrapper around `ZIO#timeout` for easy composition with the other policies. 

Effects of type `ZIO[R, E, A]` wrapped with a `Timeout` will get a `TimeoutError[E]` as error type. This has two subtypes:

* `WrappedError[E]` for non-timeout errors
* `CallTimedOut` for timeout errors.

## Usage example

```scala mdoc:silent
import zio._
import zio.duration._
import zio.clock.Clock
import nl.vroste.rezilience._
import nl.vroste.rezilience.Timeout.TimeoutError

val myEffect: ZIO[Clock, Exception, Unit] = ZIO.sleep(20.seconds)

val timeout: ZIO[Scope with Clock, Nothing, Timeout] = Timeout.make(10.seconds)

val result: ZIO[Clock, TimeoutError[Exception], Unit] = ZIO.scoped {
    timeout.flatMap { policy => 
      policy(myEffect)
    }
}

// result will be a ZIO failure with value `CallTimedOut`

```