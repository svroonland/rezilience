package nl.vroste.rezilience.config

import zio.config._
import ConfigDescriptor._
import zio.Duration

object TimeoutConfig {
  case class Config(timeout: Duration)

  val descriptor: ConfigDescriptor[Config] = zioDuration("timeout").to[Config]
}
