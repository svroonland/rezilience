package nl.vroste.rezilience

import nl.vroste.rezilience.Policy.PolicyError
import nl.vroste.rezilience.SwitchablePolicy.Mode
import zio.stm.{ STM, TRef }
import zio.{ Exit, IO, Promise, Scope, UIO, ZIO }

/**
 * A Policy that can be replaced safely at runtime
 */
trait SwitchablePolicy[E] extends Policy[E] {

  /**
   * Switches the policy to the new policy
   *
   * After completion of this effect, it is guaranteed that new calls are executed with the new policy. Calls in flight
   * before execution of the switch will be completed with the old policy.
   *
   * The old policy will be released after those in-flight calls are completed. The inner UIO signals completion of
   * release of the old policy.
   *
   * @param newPolicy
   *   The new policy to apply. Can be a policy that accepts a supertype of errors of the original policy.
   * @param mode
   *   Transition mode: Transition = Process new calls with the new policy while completing in-flight calls with the
   *   previous policy. FinishInFlight = Wait for completion of in-flight calls with the old policy before accepting
   */
  def switch[R0, E0, E2 >: E](
    newPolicy: ZIO[Scope with R0, E0, Policy[E2]],
    mode: Mode = Mode.Transition
  ): ZIO[R0, E0, UIO[Unit]]
}

object SwitchablePolicy {

  sealed trait Mode
  object Mode {
    case object Transition     extends Mode
    case object FinishInFlight extends Mode
  }

  /**
   * Creates a Policy that can be replaced safely at runtime
   */
  def make[R0, E0, E](
    initial: ZIO[Scope with R0, E0, Policy[E]]
  ): ZIO[Scope with R0, E0, SwitchablePolicy[E]] =
    for {
      scope         <- Scope.make
      policyState   <- makeInUsePolicyState[R0, E0, E](scope, initial, awaitReady = ZIO.unit)
      currentPolicy <- TRef.make(policyState).commit
    } yield new SwitchablePolicy[E] {
      override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A] =
        ZIO.acquireReleaseWith(beginCallWithPolicy)(endCallWithPolicy)(policyState => policyState.policy(f))

      def beginCallWithPolicy: IO[Nothing, PolicyState[E]] = STM.atomically {
        for {
          currentState <- currentPolicy.get
          _            <- currentState.inFlightCalls.update(_ + 1)
        } yield currentState
      }.tap(policyState => policyState.awaitReady)

      def endCallWithPolicy(usedPolicyState: PolicyState[E]): IO[Nothing, Unit] =
        STM.atomically {
          for {
            newInFlightCalls <- usedPolicyState.inFlightCalls.updateAndGet(_ - 1)
            shutdownBegan    <- usedPolicyState.shuttingDown.get
            done              = shutdownBegan && (newInFlightCalls == 0)
          } yield done
        }.flatMap { done =>
          ZIO.when(done)(usedPolicyState.shutdownComplete.succeed(())).unit
        }

      override def switch[R1, E1, E2 >: E](
        newPolicy: ZIO[Scope with R1, E1, Policy[E2]],
        mode: Mode
      ): ZIO[R1, E1, UIO[Unit]] =
        mode match {
          case Mode.Transition     =>
            switchTransition[E, E1, R1](scope, currentPolicy, newPolicy)
          case Mode.FinishInFlight =>
            switchFinishInFlight[E, E1, R1](scope, currentPolicy, newPolicy)
        }
    }

  private def switchTransition[E, E0, R0](
    scope: Scope.Closeable,
    currentPolicy: TRef[PolicyState[E]],
    newPolicy: ZIO[Scope with R0, E0, Policy[E]]
  ): ZIO[R0, E0, ZIO[Any, Nothing, Unit]] =
    for {
      newPolicyState     <- makeInUsePolicyState[R0, E0, E](scope, newPolicy, awaitReady = ZIO.unit)
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
      policyReleased     <- Promise.make[Nothing, Any]
      _                  <-
        (currentPolicyState.shutdownComplete.await *> currentPolicyState.finalizer
          .apply(Exit.unit)
          .intoPromise(policyReleased)).fork
    } yield policyReleased.await.unit

  private def switchFinishInFlight[E, E0, R0](
    scope: Scope.Closeable,
    currentPolicy: TRef[PolicyState[E]],
    newPolicy: ZIO[Scope with R0, E0, Policy[E]]
  ): ZIO[R0, E0, ZIO[Any, Nothing, Unit]] =
    for {
      markAsReady                   <- Promise.make[Nothing, Unit]
      newPolicyState                <- makeInUsePolicyState[R0, E0, E](scope, newPolicy, markAsReady.await)
      // Atomically switch the policy and mark the old one as shutting down
      switchResult                  <- STM.atomically {
                                         for {
                                           oldState <- currentPolicy.get
                                           _        <- oldState.shuttingDown.set(true)
                                           inFlight <- oldState.inFlightCalls.get
                                           _        <- currentPolicy.set(newPolicyState)
                                         } yield (oldState, inFlight)
                                       }.onInterrupt(newPolicyState.finalizer.apply(Exit.unit))
      (currentPolicyState, inFlight) = switchResult
      // From this point on, new policy calls will use the new policy but they have to
      // wait for the old policy's calls to have finished
      // Use a promise to decouple the 'await' effect from the fiber running the finalizer
      policyReleased                <- Promise.make[Nothing, Any]
      _                             <-
        (currentPolicyState.shutdownComplete.await.unless(inFlight == 0) *>
          markAsReady.succeed(()) *>
          currentPolicyState.finalizer.apply(Exit.unit).intoPromise(policyReleased)).fork
    } yield policyReleased.await.unit

  private case class PolicyState[E](
    policy: Policy[E],
    finalizer: Exit[Any, Any] => UIO[Unit],
    inFlightCalls: TRef[Long],
    awaitReady: UIO[Unit],
    shuttingDown: TRef[Boolean],
    shutdownComplete: Promise[Nothing, Unit]
  )

  private def makeInUsePolicyState[R0, E0, E](
    scope: Scope.Closeable,
    newPolicy: ZIO[Scope with R0, E0, Policy[E]],
    awaitReady: UIO[Unit]
  ): ZIO[R0, E0, PolicyState[E]] = for {
    shutdownComplete <- Promise.make[Nothing, Unit]
    inFlightCalls    <- TRef.make(0L).commit
    shuttingDown     <- TRef.make(false).commit
    newPolicyScope   <- scope.extend(Scope.make)
    finalizer         = (exit: Exit[Any, Any]) => newPolicyScope.close(exit)
    policy           <- newPolicyScope.extend[R0](newPolicy)
  } yield PolicyState(policy, finalizer, inFlightCalls, awaitReady, shuttingDown, shutdownComplete)

}
