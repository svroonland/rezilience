package nl.vroste.rezilience

import zio.{ Schedule, ZIO }
import zio.duration._
import zio.test.Assertion._
import zio.test._

object CircuitBreakerSpec extends DefaultRunnableSpec {
  sealed trait Error
  case object CircuitBreakerClosedError extends Error
  case object MyCallError               extends Error

  def spec = suite("CircuitBreaker")(
    testM("lets successful calls through") {

      CircuitBreaker.make[Error](10, 1.second, CircuitBreakerClosedError).use { cb =>
        for {
          _ <- cb.call(ZIO.unit).repeat(Schedule.recurs(20))
        } yield assertCompletes
      }
    },
    testM("fails fast after max nr failures calls") {

      CircuitBreaker.make[Error](10, 1.second, CircuitBreakerClosedError).use { cb =>
        for {
          _      <- ZIO.foreach(1 to 10)(_ => cb.call(ZIO.fail(MyCallError)).either)
          result <- cb.call(ZIO.fail(MyCallError)).either
        } yield assert(result)(isLeft(equalTo(CircuitBreakerClosedError)))
      }
    }
  )
}
