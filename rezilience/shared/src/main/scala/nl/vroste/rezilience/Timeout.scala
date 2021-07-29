package nl.vroste.rezilience

import nl.vroste.rezilience.Timeout.TimeoutError
import zio._

/**
 * Applies a timeout to effects
 *
 * Just a thin wrapper around ZIO's retry mechanisms for easy composition with other [[Policy]]s.
 */
trait Timeout { self =>

  def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, TimeoutError[E], A]

  def toPolicy: Policy[Any] = new Policy[Any] {
    override def apply[R, E1 <: Any, A](f: ZIO[R, E1, A]): ZIO[R, Policy.PolicyError[E1], A] =
      self(f).mapError(_.fold(Policy.CallTimedOut, Policy.WrappedError(_)))
  }
}

object Timeout {
  sealed trait TimeoutError[+E] { self =>
    def toException: Exception = TimeoutException(self)

    def fold[O](onTimeout: O, unwrap: E => O): O = self match {
      case CallTimedOut        => onTimeout
      case WrappedError(error) => unwrap(error)
    }
  }

  case object CallTimedOut             extends TimeoutError[Nothing]
  case class WrappedError[E](error: E) extends TimeoutError[E]

  case class TimeoutException[E](error: TimeoutError[E]) extends Exception("Timeout")

  def make(timeout: Duration): ZManaged[Has[Clock], Nothing, Timeout] = ZManaged.service[Clock].map { clock =>
    new Timeout {
      override def apply[R, E, A](f: ZIO[R, E, A]): ZIO[R, TimeoutError[E], A] =
        ZIO
          .environment[R]
          .flatMap(env =>
            f.provide(env).mapError(WrappedError(_)).timeoutFail(CallTimedOut)(timeout).provide(Has(clock))
          )
    }
  }
}
