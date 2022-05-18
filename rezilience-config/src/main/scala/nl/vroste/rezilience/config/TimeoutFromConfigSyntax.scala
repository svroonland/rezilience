package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.Timeout
import zio.config._
import zio.{ Scope, ZIO }

trait TimeoutFromConfigSyntax {
  implicit class TimeoutExtensions(self: Timeout.type) {
    def fromConfig(source: ConfigSource): ZIO[Scope, ReadError[String], Timeout] =
      for {
        config <- read(TimeoutConfig.descriptor from source)
        rl     <- rezilience.Timeout.make(config.timeout)
      } yield rl
  }
}
