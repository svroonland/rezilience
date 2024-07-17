package nl.vroste.rezilience.config

import zio.Config.int
import zio.config._

case class BulkheadConfig(maxInFlightCalls: Int, maxQueueing: Int)

object BulkheadConfig {
  implicit val config: zio.Config[BulkheadConfig] =
    (int("max-in-flight-calls") zip int("max-queueing").withDefault(32)).to[BulkheadConfig]
}
