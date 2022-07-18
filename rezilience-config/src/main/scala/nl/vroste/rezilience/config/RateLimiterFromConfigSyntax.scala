package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.RateLimiter
import zio.config._
import zio.{ Scope, ZIO }

trait RateLimiterFromConfigSyntax {
  implicit class RateLimiterExtensions(self: RateLimiter.type) {
    def fromConfig(source: ConfigSource): ZIO[Scope, ReadError[String], RateLimiter] =
      for {
        config <- read(RateLimiterConfig.descriptor from source)
        rl     <- rezilience.RateLimiter.make(config.max, config.interval)
      } yield rl
  }
}
