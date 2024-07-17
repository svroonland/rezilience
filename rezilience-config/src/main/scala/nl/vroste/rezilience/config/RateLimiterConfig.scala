package nl.vroste.rezilience.config

import zio.config._
import zio.Config._
import zio.Duration

object RateLimiterConfig {
  case class Config(max: Int, interval: Duration)

  val descriptor: zio.Config[Config] = (int("max") zip duration("interval")).to[Config]
}
