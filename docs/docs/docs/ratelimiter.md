---
layout: docs
title: Rate Limiter
permalink: docs/ratelimiter/
---

# RateLimiter
`RateLimiter` limits the number of calls to some resource to a maximum number in some interval. It is similar to Bulkhead, but while Bulkhead limits the number of concurrent calls, RateLimiter limits the rate of calls.

RateLimiter is created without type parameters and allows any effect with any environment and error channel to be called under the protection of rate limiting.

## Usage

```scala
import zio._
import zio.duration._
import nl.vroste.rezilience._

// We use Throwable as error type in this example 
def myCallToExternalResource(someInput: String): ZIO[Any, Throwable, Int] = ???

val rateLimiter: UManaged[RateLimiter] = RateLimiter.make(max = 10, interval = 1.second)

rateLimiter.use { rateLimiter =>
  val result: ZIO[Any, Throwable, Int] =
        rateLimiter(myCallToExternalResource("some input"))
       
}
```

_NOTE: for typical use cases of resource usage protection, limiting the number of concurrent calls/usage is preferable over limiting the rate of calls. See this [excellent talk by Jon Moore](https://www.youtube.com/watch?v=m64SWl9bfvk) on the subject.
