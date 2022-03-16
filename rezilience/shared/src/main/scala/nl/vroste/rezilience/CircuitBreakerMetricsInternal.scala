package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.{ State, StateChange }
import zio.Chunk
import zio.duration.Duration

import java.time.Instant

private[rezilience] final case class CircuitBreakerMetricsInternal(
  start: Instant,
  succeededCalls: Long,
  failedCalls: Long,
  rejectedCalls: Long,
  stateChanges: Chunk[StateChange],
  lastResetTime: Option[Instant]
) {

  def toUserMetrics(
    interval: Duration
  ): CircuitBreakerMetrics =
    CircuitBreakerMetrics(
      interval,
      succeededCalls,
      failedCalls,
      rejectedCalls,
      stateChanges,
      lastResetTime
    )

  def callSucceeded: CircuitBreakerMetricsInternal                                                 = copy(succeededCalls = succeededCalls + 1)
  def callRejected: CircuitBreakerMetricsInternal                                                  = copy(rejectedCalls = rejectedCalls + 1)
  def callFailed: CircuitBreakerMetricsInternal                                                    = copy(failedCalls = failedCalls + 1)
  def stateChanged(currentState: State, state: State, now: Instant): CircuitBreakerMetricsInternal =
    copy(stateChanges = stateChanges :+ StateChange(currentState, state, now))

}

private[rezilience] object CircuitBreakerMetricsInternal {

  def empty(now: Instant): CircuitBreakerMetricsInternal =
    CircuitBreakerMetricsInternal(
      start = now,
      succeededCalls = 0,
      failedCalls = 0,
      rejectedCalls = 0,
      stateChanges = Chunk.empty,
      lastResetTime = None
    )
}
