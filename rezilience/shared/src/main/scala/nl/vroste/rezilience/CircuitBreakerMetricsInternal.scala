package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.{ State, StateChange }
import zio.Chunk
import zio.duration.Duration
import zio.stm.{ TRef, USTM }

import java.time.Instant

private[rezilience] final case class CircuitBreakerMetricsInternal(
  start: TRef[Instant],
  succeededCalls: TRef[Long],
  failedCalls: TRef[Long],
  rejectedCalls: TRef[Long],
  stateChanges: TRef[Chunk[StateChange]],
  lastResetTime: TRef[Option[Instant]]
) {

  def toUserMetrics(
    interval: Duration
  ): USTM[CircuitBreakerMetrics] = for {
    succeededCalls <- succeededCalls.get
    failedCalls    <- failedCalls.get
    rejectedCalls  <- rejectedCalls.get
    stateChanges   <- stateChanges.get
    lastResetTime  <- lastResetTime.get
  } yield CircuitBreakerMetrics(
    interval = interval,
    failedCalls = failedCalls,
    succeededCalls = succeededCalls,
    rejectedCalls = rejectedCalls,
    stateChanges = stateChanges,
    lastResetTime = lastResetTime
  )

  def callSucceeded: USTM[Unit]                                                 = succeededCalls.update(_ + 1)
  def callRejected: USTM[Unit]                                                  = rejectedCalls.update(_ + 1)
  def callFailed: USTM[Unit]                                                    = failedCalls.update(_ + 1)
  def stateChanged(currentState: State, state: State, now: Instant): USTM[Unit] =
    stateChanges
      .update(_ :+ StateChange(currentState, state, now)) *> lastResetTime.set(Some(now)).when(state == State.Closed)
}

private[rezilience] object CircuitBreakerMetricsInternal {

  def makeEmpty(now: Instant): USTM[CircuitBreakerMetricsInternal] =
    for {
      start          <- TRef.make(now)
      succeededCalls <- TRef.make(0L)
      failedCalls    <- TRef.make(0L)
      rejectedCalls  <- TRef.make(0L)
      stateChanges   <- TRef.make[Chunk[StateChange]](Chunk.empty)
      lastResetTime  <- TRef.make[Option[Instant]](None)
    } yield CircuitBreakerMetricsInternal(
      start = start,
      succeededCalls = succeededCalls,
      failedCalls = failedCalls,
      rejectedCalls = rejectedCalls,
      stateChanges = stateChanges,
      lastResetTime = lastResetTime
    )
}
