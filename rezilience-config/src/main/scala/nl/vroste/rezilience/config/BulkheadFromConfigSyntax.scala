package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.{ Bulkhead, RateLimiter }
import zio.clock.Clock
import zio.config._
import zio.{ ZIO, ZManaged }

trait BulkheadFromConfigSyntax {
  implicit class BulkheadExtensions(self: RateLimiter.type) {
    def fromConfig(source: ConfigSource): ZManaged[Clock, ReadError[String], Bulkhead] =
      for {
        config <- ZIO.fromEither(read(BulkheadConfig.descriptor from source)).toManaged_
        rl     <- rezilience.Bulkhead.make(config.maxInFlightCalls, config.maxQueueing)
      } yield rl
  }
}
