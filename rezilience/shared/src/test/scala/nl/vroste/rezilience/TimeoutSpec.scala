package nl.vroste.rezilience

import nl.vroste.rezilience.Timeout.CallTimedOut
import zio.ZIO
import zio._
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._

object TimeoutSpec extends DefaultRunnableSpec {
  override def spec = suite("Timeout")(
    test("succeeds a regular call") {
      ZIO.scoped {
        for {
          timeoutPolicy <- Timeout.make(10.seconds)
          result        <- timeoutPolicy(ZIO.unit).exit
        } yield assert(result)(succeeds(anything))
      }
    },
    test("fails a call that times out") {
      ZIO.scoped {
        for {
          timeoutPolicy <- Timeout.make(10.seconds)
          fib           <- timeoutPolicy(ZIO.sleep(20.seconds)).fork
          _             <- TestClock.adjust(30.seconds)
          result        <- fib.join.exit
        } yield assert(result)(fails(equalTo(CallTimedOut)))
      }
    }
  ) @@ nonFlaky
}
