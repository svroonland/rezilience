package nl.vroste.rezilience

import nl.vroste.rezilience.Policy.WrappedError
import zio.duration.durationInt
import zio.test.Assertion.{ equalTo, fails }
import zio.test.TestAspect.{ nonFlaky, timed, timeout }
import zio.test._
import zio.{ Promise, Ref, ZIO, ZManaged }

object SwitchablePolicySpec extends DefaultRunnableSpec {

  override def spec = suite("switchable policy")(
    suite("transition mode")(
      testM("uses the new policy after switching") {
        val initialPolicy = Retry.make().map(_.toPolicy)

        val policy = SwitchablePolicy.make(initialPolicy)

        val failFirstTime: ZIO[Any, Nothing, ZIO[Any, Unit, Unit]] = for {
          ref   <- Ref.make(0)
          effect = ref.getAndUpdate(_ + 1).flatMap(count => ZIO.fail(()).when(count < 1))
        } yield effect

        policy.use { callWithPolicy =>
          for {
            e      <- failFirstTime
            _      <- callWithPolicy(e) // Should succeed
            _      <- callWithPolicy.switch(ZManaged.succeed(Policy.noop))
            e2     <- failFirstTime
            result <- callWithPolicy(e2).run // Should fail
          } yield assert(result)(fails(equalTo(WrappedError(()))))
        }
      },
      testM("does not wait for in-flight calls to finish when switching") {
        val initialPolicy = Bulkhead.make(1).map(_.toPolicy)

        val policy = SwitchablePolicy.make(initialPolicy)

        def waitForLatch = for {
          latch   <- Promise.make[Nothing, Unit]
          started <- Promise.make[Nothing, Unit]
          effect   = started.succeed(()) *> latch.await
        } yield (effect, started, latch)

        policy.use { callWithPolicy =>
          for {
            (e, started, latch) <- waitForLatch
            fib                 <- callWithPolicy(e).fork
            _                   <- started.await
            fib2                <- callWithPolicy(e).fork
            _                   <- callWithPolicy.switch(ZManaged.succeed(Policy.noop))
            _                   <- callWithPolicy(ZIO.unit) // Should return immediately with the new noop policy
            _                   <- latch.succeed(())
            _                   <- fib.join
            _                   <- fib2.join
          } yield assertCompletes
        }

      }
    ),
    suite("finish in flight mode")(
      testM("uses the new policy after switching") {
        val initialPolicy = Retry.make().map(_.toPolicy)

        val policy = SwitchablePolicy.make(initialPolicy)

        val failFirstTime: ZIO[Any, Nothing, ZIO[Any, Unit, Unit]] = for {
          ref   <- Ref.make(0)
          effect = ref
                     .getAndUpdate(_ + 1)
                     .flatMap(count => ZIO.fail(()).when(count < 1))
        } yield effect

        policy.use { callWithPolicy =>
          for {
            e      <- failFirstTime
            _      <- callWithPolicy(e) // Should succeed
            _      <- callWithPolicy.switch(ZManaged.succeed(Policy.noop), SwitchablePolicy.Mode.FinishInFlight)
            e2     <- failFirstTime
            result <- callWithPolicy(e2).run // Should fail
          } yield assert(result)(fails(equalTo(WrappedError(()))))
        }
      },
      testM("waits for in-flight calls to finish when switching") {
        val initialPolicy = Bulkhead.make(1).map(_.toPolicy)

        val policy = SwitchablePolicy.make(initialPolicy)

        def waitForLatch = for {
          latch   <- Promise.make[Nothing, Unit]
          started <- Promise.make[Nothing, Unit]
          effect   = started.succeed(()) *> latch.await
        } yield (effect, started, latch)

        policy.use { callWithPolicy =>
          for {
            (e, started, latch)        <- waitForLatch
            fib                        <- callWithPolicy(e).fork
            _                          <- started.await
            fib2                       <- callWithPolicy(e).fork
            _                          <- callWithPolicy.switch(ZManaged.succeed(Policy.noop), SwitchablePolicy.Mode.FinishInFlight)
            callWithNewPolicySucceeded <- Promise.make[Nothing, Unit]
            fib3                       <- callWithPolicy(
                                            ZIO.fail("Effect was executed before latch closed").unlessM(latch.isDone) *>
                                              callWithNewPolicySucceeded.succeed(())
                                          ).fork // Should wait until the latch
            _                          <- latch.succeed(())
            _                          <- fib3.join
            _                          <- fib.join
            _                          <- fib2.join
          } yield assertCompletes
        }

      }
    )
  ) @@ timed @@ timeout(60.seconds) @@ nonFlaky

  // TODO add tests for edge-case behavior, eg interruption
}
