package nl.vroste.rezilience.config

import zio.config._
import zio.Config._
import zio.{ durationInt, Duration }

object CircuitBreakerConfig {
  sealed trait TrippingStrategy
  object TrippingStrategy {
    case class FailureCount(maxFailures: Int) extends TrippingStrategy
    case class FailureRate(
      failureRateThreshold: Double = 0.5,
      sampleDuration: Duration,
      minThroughput: Int,
      nrSampleBuckets: Int
    ) extends TrippingStrategy
  }

  sealed trait ResetSchedule
  object ResetSchedule {
    case class ExponentialBackoff(min: Duration, max: Duration, factor: Double = 2.0) extends ResetSchedule
  }

  case class Config(strategy: TrippingStrategy, resetSchedule: ResetSchedule)

  val failureCountConfigDescriptor: zio.Config[TrippingStrategy.FailureCount] =
    (int("max-failures")).to[TrippingStrategy.FailureCount]

  val failureRateDescriptor: zio.Config[TrippingStrategy.FailureRate] =
    (double("failure-rate-threshold") zip
      duration("sample-duration").withDefault(1.minute) zip
      int("min-throughput").withDefault(10) zip
      int("nr-sample-buckets").withDefault(10)).to[TrippingStrategy.FailureRate]

  val trippingStrategyDescriptor: zio.Config[TrippingStrategy] =
    (failureCountConfigDescriptor orElseEither failureRateDescriptor).map(
      _.fold(_.asInstanceOf[TrippingStrategy], _.asInstanceOf[TrippingStrategy])
    )

  val resetScheduleDescriptor: zio.Config[ResetSchedule] =
    (duration("min").withDefault(1.second) zip
      duration("max").withDefault(1.minute) zip
      double("factor").withDefault(2.0)).to[ResetSchedule.ExponentialBackoff].asInstanceOf[zio.Config[ResetSchedule]]

  val descriptor: zio.Config[Config] =
    (trippingStrategyDescriptor.nested("tripping-strategy") zip resetScheduleDescriptor.nested("reset-schedule"))
      .to[Config]

}
