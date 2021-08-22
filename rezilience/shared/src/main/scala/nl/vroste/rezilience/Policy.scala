package nl.vroste.rezilience
import nl.vroste.rezilience.Bulkhead.BulkheadError
import nl.vroste.rezilience.CircuitBreaker.CircuitBreakerCallError
import nl.vroste.rezilience.Policy.{ flattenWrappedError, PolicyError }
import zio.stream.ZStream
import zio.{ stream, ZIO }

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
  sealed trait PolicyError[+E] { self =>
    def toException: Exception = PolicyException(self)

    def fold[O](bulkheadRejection: O, circuitBreakerOpen: O, timeout: O, unwrap: E => O): O = self match {
      case BulkheadRejection   => bulkheadRejection
      case CircuitBreakerOpen  => circuitBreakerOpen
      case CallTimedOut        => timeout
      case WrappedError(error) => unwrap(error)
    }

    override def toString: String = self match {
      case WrappedError(e)    => e.toString
      case BulkheadRejection  => "Too many queued calls"
      case CircuitBreakerOpen => "Too many failed calls"
      case CallTimedOut       => "Time out"
    }
  }

  case class WrappedError[E](e: E) extends PolicyError[E]
  case object BulkheadRejection    extends PolicyError[Nothing]
  case object CircuitBreakerOpen   extends PolicyError[Nothing]
  case object CallTimedOut         extends PolicyError[Nothing]

  case class PolicyException[E](error: PolicyError[E]) extends Exception(s"Policy error: ${error.toString}")

  def unwrap[E]: PartialFunction[PolicyError[E], E] = { case WrappedError(e) => e }

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
      circuitBreaker.widen(unwrap[E]).toPolicy compose
      rateLimiter.toPolicy compose
      retry.widen(unwrap[E]).toPolicy

  private class NoopRetry[E] extends Retry[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, E1, A] = f
    override def widen[E2](pf: PartialFunction[E2, E]): Retry[E2]      = new NoopRetry[E2]
  }

  /**
   * A policy that does not change the execution of the effect
   */
  val noop: Policy[Any] = new Policy[Any] {
    override def apply[R, E1 <: Any, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A] = f.mapError(WrappedError(_))
  }

  def noopRetry[E]: Retry[E] = new NoopRetry[E]

  private class NoopCircuitBreaker[E] extends CircuitBreaker[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, CircuitBreakerCallError[E1], A] =
      f.mapError(CircuitBreaker.WrappedError(_))
    override def widen[E2](pf: PartialFunction[E2, E]): CircuitBreaker[E2]                      = new NoopCircuitBreaker[E2]

    override val stateChanges: stream.Stream[Nothing, CircuitBreaker.StateChange] = ZStream.never
  }

  def noopCircuitBreaker[E]: CircuitBreaker[E] = new NoopCircuitBreaker[E]

  val noopBulkhead: Bulkhead = new Bulkhead {
    override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, BulkheadError[E], A] =
      task.mapError(Bulkhead.WrappedError(_))
  }

  val noopRateLimiter: RateLimiter = new RateLimiter {
    override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A] = task
  }

  def flattenWrappedError[E]: PolicyError[PolicyError[E]] => PolicyError[E] = {
    case WrappedError(e)    => e
    case CircuitBreakerOpen => CircuitBreakerOpen
    case BulkheadRejection  => BulkheadRejection
    case CallTimedOut       => CallTimedOut
  }
}
