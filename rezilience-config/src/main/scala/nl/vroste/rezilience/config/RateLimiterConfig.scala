package nl.vroste.rezilience.config

import zio.config._
import ConfigDescriptor._
import zio.Duration

object RateLimiterConfig {
  case class Config(max: Int, interval: Duration)

  val descriptor: ConfigDescriptor[Config] = (int("max") zip zioDuration("interval")).to[Config]
}
