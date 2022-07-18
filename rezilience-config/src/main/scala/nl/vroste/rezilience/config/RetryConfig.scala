package nl.vroste.rezilience.config

import zio.config._
import ConfigDescriptor._
import zio.{ durationInt, Duration }

object RetryConfig {
  case class Config(
    minDelay: Duration = 1.second,
    maxDelay: Option[Duration],
    factor: Double,
    retryImmediately: Boolean,
    maxRetries: Option[Int],
    jitter: Double
  )

  val descriptor: ConfigDescriptor[Config] = (
    zioDuration("min-delay") zip
      zioDuration("max-delay").optional zip
      double("factor").default(2.0) zip
      boolean("retry-immediately").default(false) zip
      int("max-retries").optional zip
      double("jitter").default(0.0)
  )
    .to[Config]
}
