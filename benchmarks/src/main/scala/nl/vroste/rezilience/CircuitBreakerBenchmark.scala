package nl.vroste.rezilience

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import zio.clock.Clock
import zio.internal.Platform
import zio.{ Has, Runtime, ZIO }

/*
[info] Benchmark                                                Mode  Cnt        Score       Error  Units
[info] CircuitBreakerBenchmark.withCircuitBreaker              thrpt    3   671880,791 ± 61465,917  ops/s
[info] CircuitBreakerBenchmark.withCircuitBreakerResilience4J  thrpt    3   870870,859 ± 23260,674  ops/s
[info] CircuitBreakerBenchmark.withoutCircuitBreaker           thrpt    3  1083011,452 ± 87373,868  ops/s
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class CircuitBreakerBenchmark {
  val platform: Platform = Platform.benchmark

  var runtime: Runtime.Managed[Clock with Has[CircuitBreaker[Any]]]                    = _
  var circuitBreaker: CircuitBreaker[Any]                                              = _
  var resilience4JCircuitBreaker: io.github.resilience4j.circuitbreaker.CircuitBreaker = _

  @Setup(Level.Trial)
  def createCircuitBreaker() = {
    runtime = zio.Runtime.unsafeFromLayer(
      Clock.live >+> CircuitBreaker.withMaxFailures(10).toLayer[CircuitBreaker[Any]],
      platform
    )

    circuitBreaker = runtime.unsafeRun(ZIO.service[CircuitBreaker[Any]])

    resilience4JCircuitBreaker = io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("test")
  }

  val effect = zio.clock.currentDateTime

  @Benchmark
  def withCircuitBreaker() =
    runtime.unsafeRun(
      circuitBreaker(effect).unit
    )

  @Benchmark
  def withCircuitBreakerResilience4J() =
    resilience4JCircuitBreaker.executeRunnable(new Runnable {
      override def run = {
        runtime.unsafeRun(effect)
        ()
      }
    })

  @Benchmark
  def withoutCircuitBreaker() =
    runtime.unsafeRun(effect)
}
