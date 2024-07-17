package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.CircuitBreaker.isFailureAny
import nl.vroste.rezilience.{ CircuitBreaker, Retry }
import zio.{ Scope, ZIO }

trait CircuitBreakerFromConfigSyntax {
  implicit class CircuitBreakerExtensions(self: CircuitBreaker.type) {
    def fromConfig[E](
      config: CircuitBreakerConfig,
      isFailure: PartialFunction[E, Boolean] = isFailureAny[E]
    ): ZIO[Scope, Nothing, CircuitBreaker[E]] = {

      val trippingStrategy = config.strategy match {
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
      val resetSchedule    = config.resetSchedule match {
        case ResetSchedule.ExponentialBackoff(min, max, factor) =>
          Retry.Schedules.exponentialBackoff(min, max, factor)
      }

      self.make[E](trippingStrategy, resetSchedule, isFailure)
    }
  }
}
