package nl.vroste.rezilience.config

import zio.config._
import ConfigDescriptor._

object BulkheadConfig {
  case class Config(maxInFlightCalls: Int, maxQueueing: Int)

  val descriptor: ConfigDescriptor[Config] = (int("max-in-flight-calls") zip int("max-queueing").default(32)).to[Config]
}
