package nl.vroste.rezilience

import zio.UIO
import zio.stm.TRef

// From https://fsvehla.blog/blog/2020/02/16/zio-stm-count-down-latch.html
final class CountDownLatch private (count: TRef[Int]) {
  val countDown: UIO[Unit] =
    count.update(_ - 1).commit.unit

  val await: UIO[Unit] =
    count.get.collect { case n if n <= 0 => () }.commit
}

object CountDownLatch {
  def make(count: Int): UIO[CountDownLatch] =
    TRef.make(count).map(r => new CountDownLatch(r)).commit
}
