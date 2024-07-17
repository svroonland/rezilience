package nl.vroste.rezilience.config

import zio.config._
import zio.Config._
import zio.{ durationInt, Duration }

case class RetryConfig(
  minDelay: Duration = 1.second,
  maxDelay: Option[Duration],
  factor: Double,
  retryImmediately: Boolean,
  maxRetries: Option[Int],
  jitter: Double
)

object RetryConfig {
  implicit val config: zio.Config[RetryConfig] = (
    duration("min-delay") zip
      duration("max-delay").optional zip
      double("factor").withDefault(2.0) zip
      boolean("retry-immediately").withDefault(false) zip
      int("max-retries").optional zip
      double("jitter").withDefault(0.0)
  ).to[RetryConfig]
}
