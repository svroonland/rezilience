package nl.vroste.rezilience.config

import nl.vroste.rezilience
import nl.vroste.rezilience.Retry
import zio.{ Schedule, Scope, ZIO }

trait RetryFromConfigSyntax {
  implicit class RetryExtensions(self: Retry.type) {
    def fromConfig[E](config: RetryConfig): ZIO[Scope, Nothing, Retry[Any]] = {
      val schedule = config match {
        case RetryConfig(minDelay, None, _, retryImmediately, maxRetries, jitterFactor) =>
          val baseSchedule = Schedule.spaced(minDelay)
          extendSchedule(baseSchedule, retryImmediately, maxRetries, jitterFactor)

        case RetryConfig(
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
      rezilience.Retry.make(schedule)
    }
  }

  private def extendSchedule[E](
    baseSchedule: Schedule[Any, Any, Any],
    retryImmediately: Boolean,
    maxRetries: Option[Int],
    jitterFactor: Double
  ): Schedule[Any, Any, Any] =
    (if (retryImmediately) Schedule.once else Schedule.stop) andThen
      baseSchedule.jittered(1.0 - jitterFactor, 1.0 + jitterFactor) &&
      maxRetries.fold(zio.Schedule.forever)(zio.Schedule.recurs)

}
