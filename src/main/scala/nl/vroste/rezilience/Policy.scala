package nl.vroste.rezilience
import nl.vroste.rezilience.Bulkhead.{ BulkheadError, Metrics }
import nl.vroste.rezilience.CircuitBreaker.CircuitBreakerCallError
import zio.{ UIO, ZIO }

/**
 * Represents a composition of one or more rezilience policies
 */
trait Policy[-EIn, +EOut] { self =>
  def apply[R, E1 <: EIn, A](f: ZIO[R, E1, A]): ZIO[R, EOut, A]

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
   * @param that
   * @tparam EOut2
   * @return
   */
  final def compose[EOut2, EOut3 >: EOut](that: Policy[EOut3, EOut2]): Policy[EIn, EOut2] = new Policy[EIn, EOut2] {
    override def apply[R, E1 <: EIn, A](f: ZIO[R, E1, A]): ZIO[R, EOut2, A] = that(self(f))
  }

  final def mapError[EOut2](f: EOut => EOut2): Policy[EIn, EOut2] = new Policy[EIn, EOut2] {
    override def apply[R, E1 <: EIn, A](g: ZIO[R, E1, A]): ZIO[R, EOut2, A] = self(g).mapError(f)
  }
}

object Policy {
  implicit def toPolicy[E](rateLimiter: RateLimiter): Policy[E, E] = rateLimiter.toPolicy[E]
  implicit def toPolicy[E](retry: Retry[E]): Policy[E, E]          = retry.toPolicy[E]

  sealed trait PolicyError[+E]

  case class WrappedError[E](e: E) extends PolicyError[E]
  case object BulkheadRejection    extends PolicyError[Nothing]
  case object CircuitBreakerOpen   extends PolicyError[Nothing]

  def compose[E, E1, E1A >: E1, E2](p1: Policy[E, E1], p2: Policy[E1A, E2]): Policy[E, E2] = p1.compose(p2)

  def compose[E, E1, E1A >: E1, E2, E2A >: E2, E3](
    p1: Policy[E, E1],
    p2: Policy[E1A, E2],
    p3: Policy[E2A, E3]
  ): Policy[E, E3] = p1.compose(p2).compose(p3)

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
    circuitBreaker: CircuitBreaker[PolicyError[E]] = noopCircuitBreaker,
    retry: Retry[E] = noopRetry[E]
  ): Policy[E, PolicyError[E]] = {
    val cb: Policy[PolicyError[E], PolicyError[E]] =
      circuitBreaker.toPolicy[PolicyError[E]].mapError(circuitBreakerErrorToPolicyError).mapError(flattenWrappedError)
    val b: Policy[E, PolicyError[E]]               =
      bulkhead.toPolicy[E].mapError(bulkheadErrorToPolicyError)

    val r = rateLimiter.toPolicy[PolicyError[E]]

    val retryPolicy = retry
      .widen[PolicyError[E]] { case WrappedError(e) => e }

    b compose cb compose r compose retryPolicy
  }

  def noopRetry[E]: Retry[E] = new Retry[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, E1, A] = f
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
