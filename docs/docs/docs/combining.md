---
layout: docs
title: Combining Policies
permalink: docs/combining_policies/
---

# Combining policies

The above policies can be combined into one `Policy` to combine several resilience strategies.

Many variations of policy combinations are possible, but one example is to have a `Retry` around a `RateLimiter`.

To compose policies, convert them into a `Policy` instance using `toPolicy` and use `.compose` to wrap it in another policy. For example:

```scala
val policy: ZManaged[Clock, Nothing, Policy[Any]] = for {
rateLimiter <- RateLimiter.make(1, 2.seconds)
bulkhead    <- Bulkhead.make(2)
retry       <- Retry.make(Schedule.recurs(3))
} yield bulkhead.toPolicy compose rateLimiter.toPolicy compose retry.toPolicy
```

Because of type-safety, you sometimes need to transform your individual policies to work with the errors produced by inner policies. Take for example, a Retry around a `CircuitBreaker` that you want to call with. If you want to retry on any error, a `Retry[Any]` is fine. But if you only want to use a Retry on ZIOs with error type `E` and your Retry policy defines that it only wants to retry a subset of those errors, eg `E1 <: E`, you will need to adapt it to decide what to do with the `CircuitBreakerError[E]` that is the output error type of the `CircuitBreaker`. 
