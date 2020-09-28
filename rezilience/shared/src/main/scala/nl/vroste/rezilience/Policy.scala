package nl.vroste.rezilience
import nl.vroste.rezilience.Bulkhead.{ BulkheadError, Metrics }
import nl.vroste.rezilience.CircuitBreaker.CircuitBreakerCallError
import nl.vroste.rezilience.Policy.{ flattenWrappedError, PolicyError }
import zio.{ UIO, ZIO }

/**
 * Represents a composition of one or more rezilience policies
 */
trait Policy[-E] { self =>
  def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A]

  /**
   * Apply another policy on top of this one
   *
   * The other policy gets applied before this policy, i.e. policyA compose policyB means
   * policyB {
   *  policyA {
   *    effect
   *    }
   * }
   *
   * @param that The other policy
   * @tparam E2
   * @return
   */
  def compose[E2 <: E](that: Policy[PolicyError[E2]]): Policy[E2] =
    new Policy[E2] {
      override def apply[R, E1 <: E2, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A] =
        that(self(f)).mapError(flattenWrappedError)
    }
}

object Policy {

  sealed trait PolicyError[+E]

  case class WrappedError[E](e: E) extends PolicyError[E]
  case object BulkheadRejection    extends PolicyError[Nothing]
  case object CircuitBreakerOpen   extends PolicyError[Nothing]

  /**
   * Creates a common rezilience policy that wraps calls with a bulkhead, followed by a circuit breaker,
   * followed by a rate limiter, followed by a retry policy.
   *
   * i.e. retry(withRateLimiter(withCircuitBreaker(withBulkhead(effect)))
   *
   * Each of these wraps are optional by the default values for these three policies being noop versions
   */
  def common[E](
    rateLimiter: RateLimiter = noopRateLimiter,
    bulkhead: Bulkhead = noopBulkhead,
    circuitBreaker: CircuitBreaker[E] = noopCircuitBreaker,
    retry: Retry[E] = noopRetry[E]
  ): Policy[E] =
    bulkhead.toPolicy compose
      circuitBreaker.widen[PolicyError[E]] { case WrappedError(e) => e }.toPolicy compose
      rateLimiter.toPolicy compose
      retry.widen[PolicyError[E]] { case WrappedError(e) => e }.toPolicy

  private class NoopRetry[E] extends Retry[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, E1, A] = f
    override def widen[E2](pf: PartialFunction[E2, E]): Retry[E2]      = new NoopRetry[E2]
  }

  def noopRetry[E]: Retry[E] = new NoopRetry[E]

  private class NoopCircuitBreaker[E] extends CircuitBreaker[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, CircuitBreakerCallError[E1], A] =
      f.mapError(CircuitBreaker.WrappedError(_))
    override def widen[E2](pf: PartialFunction[E2, E]): CircuitBreaker[E2]                      = new NoopCircuitBreaker[E2]
  }

  def noopCircuitBreaker[E]: CircuitBreaker[E] = new NoopCircuitBreaker[E]

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
