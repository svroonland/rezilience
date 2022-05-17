package nl.vroste.rezilience.config

import com.typesafe.config.ConfigFactory
import nl.vroste.rezilience.CircuitBreaker
import zio.ZIO
import zio.config.typesafe.TypesafeConfigSource
import zio.test._
import zio.test.Assertion._
import nl.vroste.rezilience.config.CircuitBreakerFromConfig._
import zio.duration.durationInt
import zio.test.environment.TestClock

object CircuitBreakerFromConfigSpec extends DefaultRunnableSpec {
  override def spec = suite("CircuitBreakerFromConfig")(
    testM("can read failure-count strategy from config") {
      val config = ConfigFactory.parseString(s"""
                                                | my-circuit-breaker {
                                                |  tripping-strategy {
                                                |    max-failures = 1
                                                |  }
                                                |  
                                                |  reset-schedule {
                                                |    min = 3 seconds
                                                |  }
                                                | }
                                                |""".stripMargin)

      ZIO
        .fromEither(TypesafeConfigSource.fromTypesafeConfig(config.getConfig("my-circuit-breaker")))
        .toManaged_
        .flatMap(CircuitBreaker.fromConfig(_))
        .use { cb =>
          for {
            result <- cb(ZIO.fail(())).ignore *> cb(ZIO.fail(())).ignore *> cb(ZIO.succeed(123)).either
          } yield assert(result)(isLeft(equalTo(CircuitBreaker.CircuitBreakerOpen)))
        }
    },
    testM("can read failure-rate strategy from config") {
      val config = ConfigFactory.parseString(s"""
                                                | my-circuit-breaker {
                                                |  tripping-strategy {
                                                |    failure-rate-threshold = 0.75
                                                |    sample-duration = 2 seconds
                                                |    min-throughput = 1
                                                |    nr-sample-buckets = 2
                                                |  }
                                                |  
                                                |  reset-schedule {
                                                |    min = 3 seconds
                                                |  }
                                                | }
                                                |""".stripMargin)

      ZIO
        .fromEither(TypesafeConfigSource.fromTypesafeConfig(config.getConfig("my-circuit-breaker")))
        .toManaged_
        .flatMap(CircuitBreaker.fromConfig(_))
        .use { cb =>
          for {
            _ <- cb(ZIO.fail(())).ignore
            _ <- cb(ZIO.fail(())).ignore
            _ <- cb(ZIO.succeed(()))
            _ <- cb(ZIO.fail(())).ignore

            _       <- TestClock.adjust(2.seconds)
            result1 <- cb(ZIO.succeed(123)).either

            _ <- cb(ZIO.fail(())).ignore
            _ <- cb(ZIO.fail(())).ignore
            _ <- cb(ZIO.fail(())).ignore
            _ <- cb(ZIO.fail(())).ignore

            _       <- TestClock.adjust(2.seconds)
            result2 <- cb(ZIO.succeed(123)).either
          } yield assert(result1)(isRight(anything)) && assert(result2)(
            isLeft(equalTo(CircuitBreaker.CircuitBreakerOpen))
          )
        }
    }
  )

}
