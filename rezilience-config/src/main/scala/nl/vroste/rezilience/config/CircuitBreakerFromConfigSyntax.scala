package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.CircuitBreaker.{ isFailureAny, State }
import nl.vroste.rezilience.config.CircuitBreakerConfig.{ ResetSchedule, TrippingStrategy }
import nl.vroste.rezilience.{ CircuitBreaker, Retry }
import zio.config._
import zio.{ Scope, UIO, ZIO }

trait CircuitBreakerFromConfigSyntax {
  implicit class CircuitBreakerExtensions(self: CircuitBreaker.type) {
    def fromConfig[E](
      source: ConfigSource,
      isFailure: PartialFunction[E, Boolean] = isFailureAny[E],
      onStateChange: State => UIO[Unit] = _ => ZIO.unit
    ): ZIO[Scope, ReadError[String], CircuitBreaker[E]] =
      for {
        config          <- read(CircuitBreakerConfig.descriptor from source)
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
