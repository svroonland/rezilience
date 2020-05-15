package nl.vroste
import zio.Has

package object rezilience {
  type CircuitBreaker = Has[CircuitBreaker.Service]
}
