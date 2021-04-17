package nl.vroste.rezilience

import nl.vroste.rezilience.Policy.WrappedError
import zio.duration.durationInt
import zio.test.Assertion.{ equalTo, fails }
import zio.test.TestAspect.{ nonFlaky, timeout }
import zio.test.environment.TestClock
import zio.test._
import zio.{ Promise, Ref, ZIO, ZManaged }

object SwitchablePolicySpec extends DefaultRunnableSpec {

  override def spec = suite("switchable")(
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
    testM("can switch while being used") {
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
          fib2                <- callWithPolicy(e).fork // How do we ensure that this one is enqueued..?
          _                   <- TestClock.adjust(0.seconds)
          _                   <- callWithPolicy.switch(ZManaged.succeed(Policy.noop))
          _                   <- latch.succeed(())
          _                   <- fib.join
          _                   <- fib2.join
          _                   <- callWithPolicy(e)
        } yield assertCompletes
      }

    }
  ) @@ nonFlaky @@ timeout(60.seconds)
}
