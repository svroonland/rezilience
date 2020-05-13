package nl.vroste
import zio.Has

package object rezilience {
  type CircuitBreaker[E] = Has[CircuitBreaker.Service[E]]
}
