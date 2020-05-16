package nl.vroste.rezilience
import java.time.Instant

import zio.{ clock, Ref, UIO, ZIO }
import zio.clock.Clock
import zio.duration._

/**
 * Keeps track of successful calls and failures and determines when the circuit breaker
 * should trip from Closed to Open state
 *
 * Custom implementations are supported
 */
trait TrippingStrategy {
  def onSuccess: UIO[Unit]
  def onFailure: UIO[Unit]
  def shouldTrip: UIO[Boolean]
  def onReset: UIO[Unit]
}

object TrippingStrategy {

  /**
   * For a CircuitBreaker that fails when a number of successive failures (no pun intended) has been counted
   *
   * @param maxFailures Maximum number of failures before tripping the circuit breaker
   * @return
   */
  def failureCount(maxFailures: Int) = Ref.make[Int](0).map { nrFailedCalls =>
    new TrippingStrategy {
      override def onSuccess: UIO[Unit]     = nrFailedCalls.set(0)
      override def onFailure: UIO[Unit]     = nrFailedCalls.update(_ + 1)
      override def shouldTrip: UIO[Boolean] = nrFailedCalls.get.map(_ == maxFailures)
      override def onReset: UIO[Unit]       = nrFailedCalls.set(0)
    }
  }

  /**
   * For a CircuitBreaker that fails when the fraction of failures in a sample period exceeds some threshold
   *
   * @param failureRateThreshold
   * @param sampleDuration
   * @param minThroughput
   * @return
   */
  def failureRate(
    failureRateThreshold: Double = 0.5,
    sampleDuration: Duration = 1.minute,
    minThroughput: Int = 10
  ): ZIO[Clock, Nothing, TrippingStrategy] =
    for {
      samplesRef <- Ref.make[List[Bucket]](List.empty)
      clockLayer <- ZIO.environment[Clock]
    } yield new TrippingStrategy {
      override def onSuccess: UIO[Unit] = updateSamples(true)
      override def onFailure: UIO[Unit] = updateSamples(false)
      override def onReset: UIO[Unit]   = samplesRef.set(List.empty)

      // TODO or initialize with a prefilled list?
      val nrBuckets      = 10
      val bucketDuration = sampleDuration * (1.0 / nrBuckets)

      def updateSamples(success: Boolean): UIO[Unit] =
        (samplesRef.get zip clock.currentDateTime).map {
          case (samples, currentTime) =>
            samples match {
              // No buckets yet
              case Nil =>
                Bucket(currentTime.toInstant, 0L, 0L) +: Nil

              // Start a new bucket
              case (bucket @ Bucket(bucketStartTime, _, _)) :: olderBuckets
                  if bucketStartTime.toEpochMilli + bucketDuration.toMillis >= currentTime.toInstant.toEpochMilli =>
                val newRemainingSamples =
                  if ((bucket :: olderBuckets).length >= nrBuckets) (bucket :: olderBuckets.init)
                  else bucket :: olderBuckets
                Bucket(currentTime.toInstant, 0L, 0L) +: newRemainingSamples

              // Keep using the latest bucket
              case _ =>
                samples
            }
        }.flatMap {
          case (bucket @ Bucket(_, successes, failures)) :: remainingSamples =>
            val updatedBucket =
              if (success) bucket.copy(successes = successes + 1) else bucket.copy(failures = failures + 1)

            val updatedSamples = updatedBucket +: remainingSamples

            samplesRef.set(updatedSamples)
        }.provide(clockLayer).orDie

      override def shouldTrip: UIO[Boolean] =
        for {
          samples            <- samplesRef.get
          total              = samples.foldLeft(0L) { case (acc, Bucket(_, successes, failures)) => acc + successes + failures }
          minSamplesMet      = samples.length == nrBuckets
          minThroughputMet   = total >= minThroughput
          currentFailureRate = samples.map(_.successes).sum * 1.0d / samples.map(_.total).sum
        } yield (minSamplesMet && minThroughputMet) && currentFailureRate >= failureRateThreshold

    }

  private case class Bucket(startTime: Instant, successes: Long, failures: Long) {
    def total = successes + failures
  }
}
