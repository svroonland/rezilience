package nl.vroste.rezilience.config

import zio.config._
import zio.Config._
import zio.Duration

case class TimeoutConfig(timeout: Duration)

object TimeoutConfig {
  implicit val config: zio.Config[TimeoutConfig] = duration("timeout").to[TimeoutConfig]
}
