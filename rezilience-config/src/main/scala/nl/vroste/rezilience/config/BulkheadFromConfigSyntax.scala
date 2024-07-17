package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.Bulkhead
import zio._

trait BulkheadFromConfigSyntax {

  implicit class BulkheadExtensions(self: Bulkhead.type) {
    def fromConfig(config: BulkheadConfig): ZIO[Scope, Nothing, Bulkhead] =
      rezilience.Bulkhead
        .make(config.maxInFlightCalls, config.maxQueueing)
  }
}
