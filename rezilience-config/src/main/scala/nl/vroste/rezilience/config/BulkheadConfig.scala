package nl.vroste.rezilience.config

import zio.Config.int
import zio.config._

object BulkheadConfig {
  case class Config(maxInFlightCalls: Int, maxQueueing: Int)

  val descriptor: zio.Config[BulkheadConfig.Config] =
    (int("max-in-flight-calls") zip int("max-queueing").withDefault(32)).to[Config]
}
