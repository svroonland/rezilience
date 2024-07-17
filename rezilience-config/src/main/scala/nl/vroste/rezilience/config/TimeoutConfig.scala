package nl.vroste.rezilience.config

import zio.config._
import zio.Config._
import zio.Duration

object TimeoutConfig {
  case class Config(timeout: Duration)

  val descriptor: zio.Config[Config] = duration("timeout").to[Config]
}
