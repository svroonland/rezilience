package nl.vroste.rezilience

import nl.vroste.rezilience.Timeout.CallTimedOut
import zio.ZIO
import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.test.environment.TestClock

object TimeoutSpec extends DefaultRunnableSpec {
  override def spec = suite("Timeout")(
    testM("succeeds a regular call") {
      Timeout.make(10.seconds).use { timeoutPolicy =>
        for {
          result <- timeoutPolicy(ZIO.unit).run
        } yield assert(result)(succeeds(anything))
      }
    },
    testM("fails a call that times out") {
      Timeout.make(10.seconds).use { timeoutPolicy =>
        for {
          fib    <- timeoutPolicy(ZIO.sleep(20.seconds)).fork
          _      <- TestClock.adjust(30.seconds)
          result <- fib.join.run
        } yield assert(result)(fails(equalTo(CallTimedOut)))
      }
    }
  ) @@ nonFlaky
}
