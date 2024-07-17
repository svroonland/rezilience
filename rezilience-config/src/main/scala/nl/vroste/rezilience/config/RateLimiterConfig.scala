package nl.vroste.rezilience.config

import zio.config._
import zio.Config._
import zio.Duration

case class RateLimiterConfig(max: Int, interval: Duration)

object RateLimiterConfig {
  implicit val config: zio.Config[RateLimiterConfig] = (int("max") zip duration("interval")).to[RateLimiterConfig]
}
