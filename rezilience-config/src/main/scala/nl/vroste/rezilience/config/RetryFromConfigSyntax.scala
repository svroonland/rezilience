package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.Retry
import zio.clock.Clock
import zio.config._
import zio.random.Random
import zio.{ Schedule, ZManaged }

trait RetryFromConfigSyntax {
  implicit class RetryExtensions(self: Retry.type) {
    def fromConfig[E](source: ConfigSource): ZManaged[Clock with Random, ReadError[String], Retry[Any]] =
      for {
        config  <- read(RetryConfig.descriptor from source).toManaged_
        schedule = config match {
                     case RetryConfig.Config(minDelay, None, _, retryImmediately, maxRetries, jitterFactor) =>
                       val baseSchedule = Schedule.spaced(minDelay)
                       extendSchedule(baseSchedule, retryImmediately, maxRetries, jitterFactor)

                     case RetryConfig.Config(
                           minDelay,
                           Some(maxDelay),
                           factor,
                           retryImmediately,
                           maxRetries,
                           jitterFactor
                         ) =>
                       val baseSchedule = Retry.Schedules.exponentialBackoff(minDelay, maxDelay, factor)
                       extendSchedule(baseSchedule, retryImmediately, maxRetries, jitterFactor)
                   }
        rl      <- rezilience.Retry.make(schedule)
      } yield rl
  }

  private def extendSchedule[E](
    baseSchedule: Schedule[Any, Any, Any],
    retryImmediately: Boolean,
    maxRetries: Option[Int],
    jitterFactor: Double
  ): Schedule[Random, Any, Any] =
    (if (retryImmediately) Schedule.once else Schedule.stop) andThen
      baseSchedule.jittered(1.0 - jitterFactor, 1.0 + jitterFactor) &&
      maxRetries.fold(zio.Schedule.forever)(zio.Schedule.recurs)

}
