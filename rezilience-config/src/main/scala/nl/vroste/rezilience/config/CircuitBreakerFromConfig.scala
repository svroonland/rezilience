package nl.vroste.rezilience.config

import zio.config._
import ConfigDescriptor._
import nl.vroste.rezilience
import nl.vroste.rezilience.CircuitBreaker.{ isFailureAny, State }
import nl.vroste.rezilience.{ CircuitBreaker, Retry }
import nl.vroste.rezilience.config.CircuitBreakerConfig.{ ResetSchedule, TrippingStrategy }
import zio.clock.Clock
import zio.{ UIO, ZIO, ZManaged }
import zio.duration.{ durationInt, Duration }

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

  val failureCountConfigDescriptor: ConfigDescriptor[TrippingStrategy.FailureCount] =
    (int("max-failures")).to[TrippingStrategy.FailureCount]

  val failureRateDescriptor: ConfigDescriptor[TrippingStrategy.FailureRate] =
    (double("failure-rate-threshold") |@|
      zioDuration("sample-duration").default(1.minute) |@|
      int("min-throughput").default(10) |@|
      int("nr-sample-buckets").default(10)).to[TrippingStrategy.FailureRate]

  val trippingStrategyDescriptor: ConfigDescriptor[TrippingStrategy] =
    (failureCountConfigDescriptor orElseEither failureRateDescriptor).transform[TrippingStrategy](
      _.fold(_.asInstanceOf[TrippingStrategy], _.asInstanceOf[TrippingStrategy]),
      {
        case c: TrippingStrategy.FailureCount => Left(c)
        case r: TrippingStrategy.FailureRate  => Right(r)
      }
    )

  val resetScheduleDescriptor: ConfigDescriptor[ResetSchedule] =
    (zioDuration("min").default(1.second) |@|
      zioDuration("max").default(1.minute) |@|
      double("factor").default(2.0)).to[ResetSchedule.ExponentialBackoff].asInstanceOf[ConfigDescriptor[ResetSchedule]]

  val descriptor: ConfigDescriptor[Config] =
    (nested("tripping-strategy")(trippingStrategyDescriptor) |@| nested("reset-schedule")(resetScheduleDescriptor))
      .to[Config]

}

object CircuitBreakerFromConfig {
  implicit class CircuitBreakerFromConfigSyntax(self: CircuitBreaker.type) {
    def fromConfig[E](
      source: ConfigSource,
      isFailure: PartialFunction[E, Boolean] = isFailureAny[E],
      onStateChange: State => UIO[Unit] = _ => ZIO.unit
    ): ZManaged[Clock, ReadError[String], CircuitBreaker[E]] =
      for {
        config          <- ZIO.fromEither(read(CircuitBreakerConfig.descriptor from source)).toManaged_
        trippingStrategy = config.strategy match {
                             case TrippingStrategy.FailureCount(maxFailures) =>
                               rezilience.TrippingStrategy.failureCount(maxFailures)
                             case TrippingStrategy.FailureRate(
                                   failureRateThreshold,
                                   sampleDuration,
                                   minThroughput,
                                   nrSampleBuckets
                                 ) =>
                               rezilience.TrippingStrategy.failureRate(
                                 failureRateThreshold,
                                 sampleDuration,
                                 minThroughput,
                                 nrSampleBuckets
                               )
                           }
        resetSchedule    = config.resetSchedule match {
                             case ResetSchedule.ExponentialBackoff(min, max, factor) =>
                               Retry.Schedules.exponentialBackoff(min, max, factor)
                           }
        cb              <- self.make[E](trippingStrategy, resetSchedule, isFailure, onStateChange)
      } yield cb
  }
}
