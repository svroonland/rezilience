package nl.vroste.rezilience

import zio.{ Ref, UIO, ZIO, ZManaged }

/**
 * Data structure that can store a Managed resource that can be changed safely
 */
private[rezilience] trait ManagedRef[R, E, A] {
  def get: UIO[A]
  def setAndGet(value: ZManaged[R, E, A]): ZIO[Any, E, A]
}

private[rezilience] object ManagedRef {
  def make[R, E, A](value: ZManaged[R, E, A]): ZManaged[R, E, ManagedRef[R, E, A]] =
    for {
      switch <- ZManaged.switchable[R, E, A]
      ref    <- switch(value).flatMap(Ref.make).toManaged_
      env    <- ZManaged.environment[R]
    } yield new ManagedRef[R, E, A] {
      override def get: UIO[A]                                         = ref.get
      override def setAndGet(value: ZManaged[R, E, A]): ZIO[Any, E, A] = switch(value).tap(ref.set).provide(env)
    }
}
