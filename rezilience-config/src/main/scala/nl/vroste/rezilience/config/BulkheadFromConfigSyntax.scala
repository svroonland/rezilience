package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.Bulkhead
import zio.config._
import zio.{ Scope, ZIO }

trait BulkheadFromConfigSyntax {
  implicit class BulkheadExtensions(self: Bulkhead.type) {
    def fromConfig(source: ConfigSource): ZIO[Scope, ReadError[String], Bulkhead] =
      for {
        config <- read(BulkheadConfig.descriptor from source)
        rl     <- rezilience.Bulkhead.make(config.maxInFlightCalls, config.maxQueueing)
      } yield rl
  }
}
