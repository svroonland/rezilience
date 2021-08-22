package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.{ Metrics, State, StateChange }
import zio.Chunk
import zio.duration.Duration

import java.time.Instant

private[rezilience] final case class CircuitBreakerMetricsInternal(
  start: Instant,
  succeededCalls: Long,
  failedCalls: Long,
  rejectedCalls: Long,
  stateChanges: Chunk[StateChange],
  lastResetTime: Option[Instant],
  currentState: State
) {

  def toUserMetrics(
    interval: Duration
  ): Metrics =
    Metrics(
      interval,
      succeededCalls,
      failedCalls,
      rejectedCalls,
      stateChanges,
      lastResetTime
    )

  def callSucceeded: CircuitBreakerMetricsInternal                            = copy(succeededCalls = succeededCalls + 1)
  def callRejected: CircuitBreakerMetricsInternal                             = copy(rejectedCalls = rejectedCalls + 1)
  def callFailed: CircuitBreakerMetricsInternal                               = copy(failedCalls = failedCalls + 1)
  def stateChanged(state: State, now: Instant): CircuitBreakerMetricsInternal =
    copy(stateChanges = stateChanges :+ StateChange(currentState, state, now), currentState = state)

}

private[rezilience] object CircuitBreakerMetricsInternal {

  def empty(now: Instant, currentState: State): CircuitBreakerMetricsInternal =
    CircuitBreakerMetricsInternal(
      start = now,
      succeededCalls = 0,
      failedCalls = 0,
      rejectedCalls = 0,
      stateChanges = Chunk.empty,
      lastResetTime = None,
      currentState = currentState
    )
}