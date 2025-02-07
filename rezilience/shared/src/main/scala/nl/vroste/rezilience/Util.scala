package nl.vroste.rezilience

import zio._

private object Util {
  final def nextPow2(n: Int): Int = {
    val nextPow = (Math.log(n.toDouble) / Math.log(2.0)).ceil.toInt
    Math.pow(2.0, nextPow.toDouble).toInt.max(2)
  }

  def onDurationExceeding(duration: Duration)(
    callback: Duration => UIO[Any]
  ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        callback(duration)
          .delay(duration)
          .fork
          .flatMap(fiber => zio.onExit(_ => fiber.interrupt))
    }
}
