---
layout: docs
title: General Usage
permalink: docs/general_usage/
---

# General usage

`rezilience` policies are created as `ZManaged` resources. This allows them to run background operations which are cleaned up safely after usage. Since these `ZManaged`s are just descriptions of the policy, they can be passed around to various call sites and `use`d to create many instances.

All instantiated policies are defined as traits with an `apply` method that takes a ZIO effect as parameter. Therefore a policy can be used as if it were a function taking a ZIO effect, eg:

```scala
Retry.make(...).use { retryPolicy => 
  retryPolicy(callToExternalSystem) // shorthand for retryPolicy.apply(callToExternalSystem) 
}
```

Some policies do not require any type information upon their creation, all types are inferred during usage (calling `apply` like in the example above). Other policies can have behavior that is dependent on the type of error of the effects they are applied on. They can only be applied on effects with an `E` that is a subtype of the errors that the policy is defined for. For example:

```scala
val isFailure: PartialFunction[Error, Boolean] = {
  case MyNotFatalError => false
  case _: Error        => true
}

CircuitBreaker.withMaxFailures(3, isFailure = isFailure).use { circuitBreaker => 
  circuitBreaker(callToExternalSystem) 
}
```

## Mapping errors

`rezilience` policies are type-safe in the error channel, which means that they change the error type of the effects they are applied to. For example, applying a `CircuitBreaker` to an effect of type `ZIO[Any, Throwable, Unit]` will result in a `ZIO[Any, CircuitBreakerError[Throwable], Unit]`. You will need to handle this error explicitly. Some convenience methods are made available on the `CircuitBreakerError` for this:

* `CircuitBreakerError#fold[O](circuitBreakerOpen: O, unwrap: E => O)`  
  Convert a `CircuitBreakerOpen` or a `WrappedError` into an `O`.
* `.toException`  
  Converts a `CircuitBreakerError` to a `CircuitBreakerException`.

Similar methods exist on `BulkheadError` and `PolicyError` (see [Bulkhead](bulkhead.md) and [Combining Policies](combining.md))

## ZLayer integration
You can apply `rezilience` policies at the level of an individual ZIO effect. But having to wrap all your calls in eg a rate limiter can clutter your code somewhat. When you are using the ZIO module pattern using `ZLayer`, it is also possible to integrate a `rezilience` policy with some service at the `ZLayer` level. In the spirit of aspect oriented programming, the code using your service will not be cluttered with the aspect of rate limiting.

For example:

```scala
val addRateLimiterToDatabase: ZLayer[Database with Clock, Nothing, Database] =
ZLayer.fromServiceManaged { database: Database.Service =>
  RateLimiter.make(10).map { rateLimiter =>
    new Database.Service {
      override def transfer(amount: Amount, from: Account, to: Account): ZIO[Any, Throwable, Unit] =
        rateLimiter(database.transfer(amount, from, to))
    }
  }
}

val env: ZLayer[Clock, Nothing, Database] = (Clock.live ++ databaseLayer) >>> addRateLimiterToDatabase
```

This works well for policies that do not alter the error type like RateLimiter and Retry, but for policies that do alter the error type, you will need to map eg a `CircuitBreakerOpen` to the error type in your service definition. For cases where your service's error type is `Throwable`, you can easily convert a `CircuitBreakerError`, `BulkheadError` or `PolicyError` to an `Exception` using `.toException`. Otherwise it is recommended to have something like a general `case class UnknownServiceError(e: Exception)` in your service error type, to which you can map policy errors. If that is not possible for some reason, you can also define a new service type like `ResilientDatabase` where the error types are `PolicyError[E]`.

See the [full example](rezilience/shared/src/test/scala/nl/vroste/rezilience/examples/ZLayerIntegrationExample.scala) for more.