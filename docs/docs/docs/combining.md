---
layout: docs
title: Combining Policies
permalink: docs/combining_policies/
---

# Combining policies

`rezilience` policies can be composed into one to apply several resilience strategies as one. 

A composed policy has a wider range of possible errors than an individual policy. This is made explicit by having to convert each policy to an instance of `Policy` by calling `.toPolicy`. Such a `Policy` has a slightly different signature for the `apply` method in the error type:

```scala
def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A]
```

A policy can be composed with another one using its `compose` method, which wraps another policy around it. Below is an example of wrapping a `Retry` around a `RateLimiter` around a `Bulkhead`. The for-comprehension is needed because policies are created as `ZManaged`s.

```scala
val policy: ZIO[Scope, Nothing, Policy[Any]] = for {
  rateLimiter <- RateLimiter.make(1, 2.seconds)
  bulkhead    <- Bulkhead.make(2)
  retry       <- Retry.make(Schedule.recurs(3))
} yield bulkhead.toPolicy compose rateLimiter.toPolicy compose retry.toPolicy
```

Composing policies requires some special care in handling policy errors, behavior-wise and type-wise. Take for example a retry around a circuit breaker. 

1. Behavior: what is the desired retry behavior when a circuit breaker error is encountered? Should the call be retried or the error passed through to the caller? 

2. Types: because a `Retry` is created with a `Schedule` that expects a certain type `E` of errors as input, a `Retry[E]` cannot be applied on `ZIO[R, CircuitBreakerError[E], A]` effects.

For these cases, the `Retry` and `CircuitBreaker` policies have a `widen` method that can adapt them to a diferent type of error. For example to adapt a `Retry[Throwable]` to a `Retry[PolicyError[Throwable]]`:

```scala
val retry: Retry[Throwable] = ???
val retryComposable = retry.widen[PolicyError[Throwable]] { case Policy.WrappedError(e) => e }
```

The partial function above is made available as `Policy#unwrap[E]` for convenience, so that the above can be written as

```scala
val retryComposable: Retry[PolicyError[Throwable]] = retry.widen(Policy.unwrap[Throwable])
```

Many variations of policy combinations are possible. The `polly` project has some good advice for the order in which to compose policies: https://github.com/App-vNext/Polly/wiki/PolicyWrap#usage-recommendations.