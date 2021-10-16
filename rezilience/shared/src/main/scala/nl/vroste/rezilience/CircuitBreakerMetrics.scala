package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.{ State, StateChange }
import zio.Chunk
import zio.duration.{ durationInt, Duration }

import java.time.Instant

object CircuitBreakerMetrics {
  val empty = CircuitBreakerMetrics(0.seconds, 0, 0, 0, Chunk.empty, None)
}

final case class CircuitBreakerMetrics(
  /**
   * Interval in which these metrics were collected
   */
  interval: Duration,
  /**
   * Number of calls that failed in the interval
   */
  failedCalls: Long,
  /**
   * Number of calls that succeeded in the interval
   */
  succeededCalls: Long,
  /**
   * Number of calls that were rejected in the interval
   */
  rejectedCalls: Long,
  /**
   * All state changes made in the metrics interval
   */
  stateChanges: Chunk[StateChange], // TODO should be ordered?

  /**
   * Time of the last reset to the Closed state
   */
  lastResetTime: Option[Instant]
) {
  def successRate: Double =
    if (failedCalls + succeededCalls > 0) failedCalls * 1.0 / (succeededCalls + failedCalls * 1.0) else 0.0

  def numberOfResets: Int = stateChanges.count(_.to == State.Closed)

  override def toString: String                             =
    Seq(
      ("interval", interval.getSeconds, "s"),
      ("succeeded calls", succeededCalls, ""),
      ("failed calls", failedCalls, ""),
      ("success rate", successRate * 100.0, "%"),
      ("number of state changes", stateChanges.size, ""),
      ("last reset time", lastResetTime.getOrElse("n/a"), "")
    ).map { case (name, value, unit) => s"${name}=${value}${if (unit.isEmpty) "" else " " + unit}" }.mkString(", ")

  /**
   * Combines the metrics
   */
  def +(that: CircuitBreakerMetrics): CircuitBreakerMetrics = copy(
    interval = interval plus that.interval,
    failedCalls = failedCalls + that.failedCalls,
    succeededCalls = succeededCalls + that.succeededCalls,
    rejectedCalls = rejectedCalls + that.rejectedCalls,
    stateChanges = stateChanges ++ that.stateChanges,
    lastResetTime = (lastResetTime, that.lastResetTime) match {
      case (Some(t1), Some(t2)) if t2 isAfter t1 => Some(t2)
      case (Some(t1), Some(t2)) if t1 isAfter t2 => Some(t1)
      case (Some(t1), None)                      => Some(t1)
      case (None, Some(t2))                      => Some(t2)
      case _                                     => None
    }
  )
}
