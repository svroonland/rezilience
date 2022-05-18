package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.Timeout
import zio.ZManaged
import zio.clock.Clock
import zio.config._

trait TimeoutFromConfigSyntax {
  implicit class TimeoutExtensions(self: Timeout.type) {
    def fromConfig(source: ConfigSource): ZManaged[Clock, ReadError[String], Timeout] =
      for {
        config <- read(TimeoutConfig.descriptor from source).toManaged_
        rl     <- rezilience.Timeout.make(config.timeout)
      } yield rl
  }
}
