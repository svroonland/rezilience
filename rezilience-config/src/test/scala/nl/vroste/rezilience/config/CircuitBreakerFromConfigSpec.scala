package nl.vroste.rezilience.config

import com.typesafe.config.ConfigFactory
import nl.vroste.rezilience.CircuitBreaker
import zio.config.typesafe.TypesafeConfigSource
import zio.test.Assertion._
import zio.test._
import zio.{ durationInt, ZIO }

object CircuitBreakerFromConfigSpec extends ZIOSpecDefault {
  override def spec = suite("CircuitBreakerFromConfig")(
    test("can read failure-count strategy from config") {
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

      val configSource = TypesafeConfigSource.fromTypesafeConfig(ZIO.succeed(config.getConfig("my-circuit-breaker")))

      for {
        cb     <- CircuitBreaker.fromConfig(configSource)
        result <- cb(ZIO.fail(())).ignore *> cb(ZIO.fail(())).ignore *> cb(ZIO.succeed(123)).either
      } yield assert(result)(isLeft(equalTo(CircuitBreaker.CircuitBreakerOpen)))
    },
    test("can read failure-rate strategy from config") {
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

      val configSource = TypesafeConfigSource.fromTypesafeConfig(ZIO.succeed(config.getConfig("my-circuit-breaker")))

      for {
        cb <-
          CircuitBreaker.fromConfig(configSource)
        _  <- cb(ZIO.fail(())).ignore
        _  <- cb(ZIO.fail(())).ignore
        _  <- cb(ZIO.succeed(()))
        _  <- cb(ZIO.fail(())).ignore

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
  )

}
