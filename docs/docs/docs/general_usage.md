---
layout: docs
title: General Usage
permalink: docs/general_usage/
---

# General usage

`rezilience` policies are created as `ZManaged` resources. This allows them to run background operations which are cleaned up safely after usage. Since these `ZManaged`s are just descriptions of the policy, they can be passed around to various call sites and `use`d to create many instances.

All instantiated policies are defined as traits with an `apply` method that takes a ZIO effect as parameter. Therefore a policy can be used as if it were a function taking a ZIO effect, eg:

```scala
Retry.make(...).use { retry => 
  retry(callToExternalSystem) // shorthand for retry.apply(callToExternalSystem) 
}
```

Policies can be applied to any type of `ZIO[R, E, A]` effect, although some policies have an upper bound for `E` depending on how they are created. Some policies alter the return error type, others leave it as is:

| Policy         | Error type upper bound                              | Result type                         |
|----------------|-----------------------------------------------------|-------------------------------------|
| CircuitBreaker | `Any`, or `E` (when `isFailure` parameter is used)   | `ZIO[R, CircuitBreakerError[E], A]` |
| RateLimiter    | `Any`                                               | `ZIO[R, E, A]`                      |
| Bulkhead       | `Any`                                               | `ZIO[R, BulkheadError[E], A]`       |
| Retry          | `Any`, or `E` when a `Schedule[Env, E, Out]` is used | `ZIO[R, E, A]`                      |

## Mapping errors

`rezilience` policies are type-safe in the error channel, which means that some of them change the error type of the effects they are applied to (see table above). For example, applying a `CircuitBreaker` to an effect of type `ZIO[Any, Throwable, Unit]` will result in a `ZIO[Any, CircuitBreakerError[Throwable], Unit]`. 

This `CircuitBreakerError` has two subtypes: 
* `case object CircuitBreakerOpen`: the error when the circuit breaker has tripped and no attempt to make the call has been made
* `case class WrappedError[E](error: E)`: the error coming from the call
 
By having this datatype for errors, `rezilience` requires you to be explicit in how you want to handle circuit breaker errors, in line with the rest of ZIO's strategy for typed error handling. At a higher level in your application you may want to inform the user that a system is temporarily not available or execute some  fallback logic. Several conveniences are available for dealing with circuit breaker errors:

* `CircuitBreakerError#fold[O](circuitBreakerOpen: O, unwrap: E => O)`  
  Convert a `CircuitBreakerOpen` or a `WrappedError` into an `O`.
* `CircuitBreakerError#toException`  
  Converts a `CircuitBreakerError` to a `CircuitBreakerException`.
  
For example:

```scala
sealed trait MyServiceErrorType
case object SystemNotInTheMood extends MyServiceErrorType
case object UnknownServiceError extends MyServiceErrorType

def callExternalSystem(someInput: String): ZIO[Any, MyServiceErrorType, Int] = 
  ZIO.succeed(someInput.length)

val result1: ZIO[Any, CircuitBreakerError[MyServiceErrorType], Int] = 
  circuitBreaker(callExternalSystem("1234"))

// Map the CircuitBreakerError back onto an UnknownServiceError
val result2: ZIO[Any, MyServiceErrorType, Int] = 
  result1.mapError(policyError => policyError.fold(UnknownServiceError, identity))

// Or turn it into an exception
val result3: ZIO[Any, Throwable, Int] =
  result1.mapError(policyError => policyError.toException)
```
  
Similar methods exist on `BulkheadError` and `PolicyError` (see [Bulkhead](./bulkhead) and [Combining Policies](./combining))

## ZLayer integration
You can apply `rezilience` policies at the level of an individual ZIO effect. But having to wrap all your calls in eg a rate limiter can clutter your code somewhat. When you are using the [ZIO module pattern](https://zio.dev/docs/howto/howto_use_layers) using `ZLayer`, it is also possible to integrate a `rezilience` policy with some service at the `ZLayer` level. In the spirit of aspect oriented programming, the code using your service will not be cluttered with the aspect of rate limiting.

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

For policies where the result type has a different `E` you will need to map the error back to your own `E`. An option is to have something like a general `case class UnknownServiceError(e: Exception)` in your service error type, to which you can map the policy errors. If that is not possible for some reason, you can also define a new service type like `ResilientDatabase` where the error types are `PolicyError[E]`.

See the [full example](rezilience/shared/src/test/scala/nl/vroste/rezilience/examples/ZLayerIntegrationExample.scala) for more.