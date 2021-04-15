package nl.vroste.rezilience

import zio.{ Ref, UIO, ZIO, ZManaged }

/**
 * Data structure that can store a Managed resource that can be changed safely
 */
private[rezilience] trait ManagedRef[R, E, A] {
  def get: UIO[A]
  def setAndGet(value: ZManaged[R, E, A]): ZIO[R, E, A]
}

private[rezilience] object ManagedRef {
  def make[R, E, A](value: ZManaged[R, E, A]): ZManaged[R, E, ManagedRef[R, E, A]] =
    for {
      switch <- ZManaged.switchable[R, E, A]
      ref    <- switch(value).flatMap(Ref.make).toManaged_
    } yield new ManagedRef[R, E, A] {
      override def get: UIO[A]                                       = ref.get
      // TODO this is not atomic, so not concurrent-safe
      override def setAndGet(value: ZManaged[R, E, A]): ZIO[R, E, A] = switch(value).tap(ref.set)
    }
}
