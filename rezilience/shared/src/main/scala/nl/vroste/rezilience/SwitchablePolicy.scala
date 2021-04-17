package nl.vroste.rezilience

import nl.vroste.rezilience.Policy.PolicyError
import zio.stream.ZStream
import zio.{ Promise, Queue, UIO, ZIO, ZManaged }

/**
 * A Policy that can be replaced safely at runtime
 */
trait SwitchablePolicy[R0, E0, E] extends Policy[E] {

  /**
   * Switches the policy to the new policy
   *
   * After completion of this effect, calls will be executed with the new policy. Calls in flight before that moment
   * will be completed with the old policy. The old policy will be released after those in-flight calls are completed.
   */
  def switch(newPolicy: ZManaged[R0, E0, Policy[E]]): ZIO[R0, E0, Unit]
}

object SwitchablePolicy {

  /**
   * Creates a Policy that can be replaced safely at runtime
   */
  def make[R0, E0, E](initial: ZManaged[R0, E0, Policy[E]]): ZManaged[R0, E0, SwitchablePolicy[R0, E0, E]] =
    for {
      policyQueue <- Queue.bounded[UIO[ZManaged[R0, E0, Policy[E]]]](1).toManaged_
      actionQueue <- Queue.unbounded[Policy[E] => UIO[Unit]].toManaged_
      _           <- policyQueue.offer(UIO(initial)).toManaged_
      policies     = ZStream
                       .fromQueue(policyQueue)
                       .flatMapParSwitch(1) { getPolicy =>
                         ZStream.managed(ZManaged.unwrap(getPolicy)).flatMap { policyInstance =>
                           ZStream
                             .fromQueue(actionQueue)
                             .mapM(action => action(policyInstance))
                         }
                       }
      _           <- policies.runDrain.forkManaged
    } yield new SwitchablePolicy[R0, E0, E] {
      // What do we do with in-progress calls.. Can't wait for them, since that would block policy usage while draining
      // Ideally you'd like to finish the in-progress ones with the old policy and simultaneously switch to the new one,
      // although for rate limiting that would give you double rates in the transition period..
      override def switch(newPolicy: ZManaged[R0, E0, Policy[E]]): ZIO[R0, E0, Unit] =
        for {
          policyInstalled <- Promise.make[Nothing, Unit]
          _               <- policyQueue.offer(policyInstalled.succeed(()) as newPolicy).unit
          _               <- policyInstalled.await
        } yield ()

      override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A] = for {
        policyForAction <- Promise.make[Nothing, Policy[E]]
        done            <- Promise.make[Nothing, Unit]

        action = (policy: Policy[E]) => policyForAction.succeed(policy) *> done.await

        onInterruptOrCompletion = done.succeed(())
        result                 <-
          ZManaged
            .makeInterruptible_(actionQueue.offer(action).onInterrupt(onInterruptOrCompletion))(onInterruptOrCompletion)
            .use_(policyForAction.await.flatMap(policy => policy.apply(f)))
      } yield result
    }
}
