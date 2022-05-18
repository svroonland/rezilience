---
layout: docs
title: Switching Policies
permalink: docs/switching_policies/
---

# Switching policies

`rezilience` policies can be switched at runtime to modify policy characteristics.

## Example

```scala
import zio._
import nl.vroste.rezilience._

val initialPolicy: ZManaged[Clock, Nothing, Policy[Any]] = RateLimiter.make(1, 1.seconds).map(_.toPolicy)
val newPolicy: ZManaged[Clock, Nothing, Policy[Any]] = RateLimiter.make(10, 1.seconds).map(_.toPolicy)

val policy: ZManaged[Clock, Nothing, SwitchablePolicy[Any]] =
  SwitchablePolicy.make(initialPolicy)

policy.use { policy =>
  for {
    _ <- policy.apply(ZIO.effect("Something"))
    switchComplete <- policy.switch(newPolicy, mode = SwitchablePolicy.Mode.Transition)

    // At this moment, any new calls will run with the new policy
    // Optionally you can await the release of the old policy:
    // _ <- switchComplete
    _ <- policy.apply(ZIO.effect("Something else"))
  } yield ()
}
```

## Behavior

Policies can be switched with two modes:
* Transition   
  Process new calls with the new policies while allowing in-flight calls to finish with the old policy.
* Finish In-Flight  
  Hold new calls until all in-flight calls have completed.

By working with `ZManaged`s of `Policy`, policies can be released and switched in a controlled manner.

Like all other policies, a call made with a `SwitchablePolicy` can be interrupted safely, also while a policy switch is in flight.

Keep in mind the effect of switching mode for different policies. As an example, consider switching a RateLimiter policy from 10 calls per second to 20 calls per second where the average call takes more than 100 ms. 
In `FinishInFlight` mode, the effective rate during the switch will be less than 10, until all calls have completed. Also there may be a spike when the new rate begins with 20. 
In `Transition` mode, the effective rate may be _higher_ than 20. 