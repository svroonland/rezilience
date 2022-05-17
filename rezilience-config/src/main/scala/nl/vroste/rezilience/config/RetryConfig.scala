package nl.vroste.rezilience.config

import zio.config._
import ConfigDescriptor._
import zio.duration.{ durationInt, Duration }

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
    zioDuration("min-delay") |@|
      zioDuration("max-delay").optional |@|
      double("factor").default(2.0) |@|
      boolean("retry-immediately").default(false) |@|
      int("max-retries").optional |@|
      double("jitter").default(0.0)
  )
    .to[Config]
}
