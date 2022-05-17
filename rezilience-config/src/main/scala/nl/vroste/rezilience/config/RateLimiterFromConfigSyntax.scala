package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.RateLimiter
import zio.clock.Clock
import zio.config._
import zio.{ ZIO, ZManaged }

trait RateLimiterFromConfigSyntax {
  implicit class RateLimiterExtensions(self: RateLimiter.type) {
    def fromConfig(source: ConfigSource): ZManaged[Clock, ReadError[String], RateLimiter] =
      for {
        config <- ZIO.fromEither(read(RateLimiterConfig.descriptor from source)).toManaged_
        rl     <- rezilience.RateLimiter.make(config.max, config.interval)
      } yield rl
  }
}
