package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.RateLimiter
import zio.{ Scope, ZIO }

trait RateLimiterFromConfigSyntax {
  implicit class RateLimiterExtensions(self: RateLimiter.type) {
    def fromConfig(config: RateLimiterConfig): ZIO[Scope, Nothing, RateLimiter] =
      rezilience.RateLimiter.make(config.max, config.interval)
  }
}
