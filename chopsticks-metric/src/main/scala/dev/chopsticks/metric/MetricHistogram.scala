package dev.chopsticks.metric

import zio.ZIO
import zio.clock.Clock

trait MetricHistogram {
  def observe(value: Double): Unit
  def observe(value: Long): Unit = observe(value.toDouble)
  def observe(value: Int): Unit = observe(value.toDouble)

  def timeM[R, E, A](f: ZIO[R, E, A]): ZIO[R with Clock, E, A] = {
    f.timed.map {
      case (d, v) =>
        observe(d.toNanos)
        v
    }
  }
}