package nl.vroste.rezilience.future

import scala.concurrent.duration.Duration

object Util {
  implicit def scalaDurationAsZio(d: Duration): zio.duration.Duration = zio.duration.Duration.fromScala(d)
}
