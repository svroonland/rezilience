package nl.vroste.rezilience.config

import com.typesafe.config.ConfigFactory
import nl.vroste.rezilience.CircuitBreaker
import zio.ZIO
import zio.config.typesafe.TypesafeConfigSource
import zio.test._
import zio.test.Assertion._
import nl.vroste.rezilience.config.CircuitBreakerFromConfig._

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
        .flatMap(CircuitBreaker.fromConfig)
        .use { cb =>
          for {
            result <- cb(ZIO.fail(())).ignore *> cb(ZIO.fail(())).ignore *> cb(ZIO.succeed(123)).either
          } yield assert(result)(isLeft(equalTo(CircuitBreaker.CircuitBreakerOpen)))
        }
    }
  )

}
