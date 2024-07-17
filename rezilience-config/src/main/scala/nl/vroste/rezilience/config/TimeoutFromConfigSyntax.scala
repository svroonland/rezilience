package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.Timeout
import zio.{ Scope, ZIO }

trait TimeoutFromConfigSyntax {
  implicit class TimeoutExtensions(self: Timeout.type) {
    def fromConfig(config: TimeoutConfig): ZIO[Scope, Nothing, Timeout] =
      rezilience.Timeout.make(config.timeout)
  }
}
