package nl.vroste.rezilience
import nl.vroste.rezilience.Bulkhead.{ BulkheadError, Metrics }
import nl.vroste.rezilience.CircuitBreaker.CircuitBreakerCallError
import nl.vroste.rezilience.PolicyWrap.PolicyError
import zio.{ Schedule, UIO, ZIO }
import zio.clock.Clock

/**
 * Represents a combination of rezilience policies
 */
trait PolicyWrap[-E] {
  def apply[R, E1 <: E, A](task: ZIO[R, E1, A]): ZIO[R with Clock, PolicyError[E1], A]
}

object PolicyWrap {
  sealed trait PolicyError[+E]

  case class WrappedError[E](e: E) extends PolicyError[E]
  case object BulkheadRejection    extends PolicyError[Nothing]
  case object CircuitBreakerOpen   extends PolicyError[Nothing]

  /**
   * Creates a rezilience policy that wraps calls with a circuit breaker, followed by a bulkhead,
   * followed by a rate limiter, followed by a retry policy.
   *
   * i.e. retry(withRateLimiter(withBulkhead(withCircuitBreaker(effect)))
   *
   * Each of these wraps are optional by the default values for these three policies being noop versions
   */
  def make[E](
    rateLimiter: RateLimiter = noopRateLimiter,
    bulkhead: Bulkhead = noopBulkhead,
    circuitBreaker: CircuitBreaker[E] = noopCircuitBreaker,
    retrySchedule: Schedule[Any, PolicyError[E], Any] = Schedule.stop
  ): PolicyWrap[E] = new PolicyWrap[E] {
    override def apply[R, E1 <: E, A](task: ZIO[R, E1, A]): ZIO[R with Clock, PolicyError[E1], A] =
      rateLimiter {
        bulkhead {
          circuitBreaker(task)
            .mapError(circuitBreakerErrorToPolicyError)
        }
          .mapError(bulkheadErrorToPolicyError)
          .mapError(flattenWrappedError)
      }.retry(retrySchedule)
  }

  def noopCircuitBreaker[E]: CircuitBreaker[E] = new CircuitBreaker[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, CircuitBreakerCallError[E1], A] =
      f.mapError(CircuitBreaker.WrappedError(_))
  }

  val noopBulkhead: Bulkhead = new Bulkhead {
    override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, BulkheadError[E], A] =
      task.mapError(Bulkhead.WrappedError(_))

    override def metrics: UIO[Metrics] = UIO.succeed(Metrics.apply(0, 0))
  }

  val noopRateLimiter: RateLimiter = new RateLimiter {
    override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A] = task
  }

  def circuitBreakerErrorToPolicyError[E]: CircuitBreakerCallError[E] => PolicyError[E] = {
    case CircuitBreaker.CircuitBreakerOpen => CircuitBreakerOpen
    case CircuitBreaker.WrappedError(e)    => WrappedError(e)
  }

  def bulkheadErrorToPolicyError[E]: BulkheadError[E] => PolicyError[E] = {
    case Bulkhead.BulkheadRejection => BulkheadRejection
    case Bulkhead.WrappedError(e)   => WrappedError(e)
  }

  def flattenWrappedError[E]: PolicyError[PolicyError[E]] => PolicyError[E] = {
    case WrappedError(e)    => e
    case CircuitBreakerOpen => CircuitBreakerOpen
    case BulkheadRejection  => BulkheadRejection
  }
}
