package nl.vroste.rezilience.config

import zio.Config._
import zio.config._
import zio.{ durationInt, Duration }

case class CircuitBreakerConfig(strategy: TrippingStrategy, resetSchedule: ResetSchedule)

sealed trait TrippingStrategy

object TrippingStrategy {
  case class FailureCount(maxFailures: Int) extends TrippingStrategy

  object FailureCount {
    implicit val config: zio.Config[TrippingStrategy.FailureCount] =
      (int("max-failures")).to[TrippingStrategy.FailureCount]
  }

  case class FailureRate(
    failureRateThreshold: Double = 0.5,
    sampleDuration: Duration,
    minThroughput: Int,
    nrSampleBuckets: Int
  ) extends TrippingStrategy

  object FailureRate {
    implicit val config: zio.Config[TrippingStrategy.FailureRate] =
      (double("failure-rate-threshold") zip
        duration("sample-duration").withDefault(1.minute) zip
        int("min-throughput").withDefault(10) zip
        int("nr-sample-buckets").withDefault(10)).to[TrippingStrategy.FailureRate]

  }

  implicit val config: zio.Config[TrippingStrategy] =
    (FailureCount.config orElseEither FailureRate.config).map(
      _.fold(_.asInstanceOf[TrippingStrategy], _.asInstanceOf[TrippingStrategy])
    )
}

sealed trait ResetSchedule
object ResetSchedule {
  case class ExponentialBackoff(min: Duration, max: Duration, factor: Double = 2.0) extends ResetSchedule

  implicit val config: zio.Config[ResetSchedule] =
    (duration("min").withDefault(1.second) zip
      duration("max").withDefault(1.minute) zip
      double("factor").withDefault(2.0)).to[ResetSchedule.ExponentialBackoff].asInstanceOf[zio.Config[ResetSchedule]]
}

object CircuitBreakerConfig {

  implicit val config: zio.Config[CircuitBreakerConfig] =
    (TrippingStrategy.config.nested("tripping-strategy") zip ResetSchedule.config.nested("reset-schedule"))
      .to[CircuitBreakerConfig]
}
