package nl.vroste.rezilience

import nl.vroste.rezilience.Policy.PolicyError
import zio.stm.{ STM, TRef }
import zio.{ Exit, IO, Promise, UIO, ZIO, ZManaged }

/**
 * A Policy that can be replaced safely at runtime
 */
trait SwitchablePolicy[R0, E0, E] extends Policy[E] {

  /**
   * Switches the policy to the new policy
   *
   * After completion of this effect, new calls will be executed with the new policy. Calls in flight before that moment
   * will be completed with the old policy.
   *
   * The old policy will be released after those in-flight calls are completed.
   * The inner UIO signals completion of release of the old policy.
   */
  def switch(newPolicy: ZManaged[R0, E0, Policy[E]]): ZIO[R0, E0, UIO[Unit]]
}

object SwitchablePolicy {

  /**
   * Creates a Policy that can be replaced safely at runtime
   */
  def make[R0, E0, E](initial: ZManaged[R0, E0, Policy[E]]): ZManaged[R0, E0, SwitchablePolicy[R0, E0, E]] = {
    def makeInUsePolicyState(
      scope: ZManaged.Scope,
      newPolicy: ZManaged[R0, E0, Policy[E]]
    ): ZIO[R0, E0, InUsePolicyState[E]] = for {
      newDone            <- Promise.make[Nothing, Unit]
      newInUse           <- TRef.make(0L).commit
      newShutdownBegan   <- TRef.make(false).commit
      r                  <- scope.apply(newPolicy)
      (finalizer, policy) = r
    } yield InUsePolicyState[E](policy, finalizer, newInUse, newShutdownBegan, newDone)

    for {
      scope         <- ZManaged.scope
      policyState   <- makeInUsePolicyState(scope, initial).toManaged_
      currentPolicy <- STM.atomically(TRef.make(policyState)).toManaged_
    } yield new SwitchablePolicy[R0, E0, E] {
      override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A] =
        ZManaged.make(beginCallWithPolicy)(endCallWithPolicy).map(_.policy).use { policy =>
          policy.apply(f)
        }

      override def switch(newPolicy: ZManaged[R0, E0, Policy[E]]): ZIO[R0, E0, UIO[Unit]] =
        for {
          newPolicyState     <- makeInUsePolicyState(scope, newPolicy)
          // Atomically switch the policy and mark the old one as shutting down
          currentPolicyState <- STM.atomically {
                                  for {
                                    oldState <- currentPolicy.get
                                    _        <- oldState.shuttingDown.set(true)
                                    _        <- currentPolicy.set(newPolicyState)
                                  } yield oldState
                                }.onInterrupt(newPolicyState.finalizer.apply(Exit.unit))
          // From this point on, new policy calls will use the new policy
          // Use a promise to decouple the 'await' effect from the fiber running the finalizer
          policyReleased     <- Promise.make[Nothing, Unit]
          complete           <-
            (currentPolicyState.shutdownComplete.await *> currentPolicyState.finalizer
              .apply(Exit.unit)
              .to(policyReleased)).unit.fork
        } yield policyReleased.await

      private def beginCallWithPolicy: IO[Nothing, InUsePolicyState[E]] = STM.atomically {
        for {
          currentState <- currentPolicy.get
          _            <- currentState.inUse.update(_ + 1)
        } yield currentState
      }

      private def endCallWithPolicy(currentState: InUsePolicyState[E]): IO[Nothing, Unit] =
        STM.atomically {
          for {
            newInUse      <- currentState.inUse.updateAndGet(_ - 1)
            shutdownBegan <- currentState.shuttingDown.get
            done           = shutdownBegan && (newInUse == 0)
          } yield done
        }.flatMap { done =>
          ZIO.when(done)(currentState.shutdownComplete.succeed(()))
        }

    }
  }

  private case class InUsePolicyState[E](
    policy: Policy[E],
    finalizer: ZManaged.Finalizer,
    inUse: TRef[Long],
    shuttingDown: TRef[Boolean],
    shutdownComplete: Promise[Nothing, Unit]
  )

}
