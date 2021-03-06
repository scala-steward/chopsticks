package dev.chopsticks.fp

import zio.{Has, URLayer, ZIO, ZManaged}

final class ZRunnable1[A, R, E, V](fn: A => ZIO[R, E, V]) {
  def toLayer[S: zio.Tag](serviceFactory: (A => ZIO[Any, E, V]) => S): URLayer[R, Has[S]] = {
    ZManaged
      .access[R](env => {
        val newFn = (arg: A) => fn(arg).provide(env)
        serviceFactory(newFn)
      })
      .toLayer
  }

  def toZIO: ZIO[R, E, A => ZIO[Any, E, V]] = {
    ZIO
      .access[R](env => {
        (arg: A) => fn(arg).provide(env)
      })
  }
}

final class ZRunnable2[A1, A2, R, E, V](fn: (A1, A2) => ZIO[R, E, V]) {
  def toLayer[S: zio.Tag](serviceFactory: ((A1, A2) => ZIO[Any, E, V]) => S): URLayer[R, Has[S]] = {
    ZManaged
      .access[R](env => {
        val newFn = (arg1: A1, arg2: A2) => fn(arg1, arg2).provide(env)
        serviceFactory(newFn)
      })
      .toLayer
  }

  def toZIO: ZIO[R, E, (A1, A2) => ZIO[Any, E, V]] = {
    ZIO
      .access[R](env => {
        (arg1: A1, arg2: A2) => fn(arg1, arg2).provide(env)
      })
  }
}

final class ZRunnable3[A1, A2, A3, R, E, V](fn: (A1, A2, A3) => ZIO[R, E, V]) {
  def toLayer[S: zio.Tag](serviceFactory: ((A1, A2, A3) => ZIO[Any, E, V]) => S): URLayer[R, Has[S]] = {
    ZManaged
      .access[R](env => {
        val newFn = (arg1: A1, arg2: A2, arg3: A3) => fn(arg1, arg2, arg3).provide(env)
        serviceFactory(newFn)
      })
      .toLayer
  }

  def toZIO: ZIO[R, E, (A1, A2, A3) => ZIO[Any, E, V]] = {
    ZIO
      .access[R](env => {
        (arg1: A1, arg2: A2, arg3: A3) => fn(arg1, arg2, arg3).provide(env)
      })
  }
}

final class ZRunnable4[A1, A2, A3, A4, R, E, V](fn: (A1, A2, A3, A4) => ZIO[R, E, V]) {
  def toLayer[S: zio.Tag](serviceFactory: ((A1, A2, A3, A4) => ZIO[Any, E, V]) => S): URLayer[R, Has[S]] = {
    ZManaged
      .access[R](env => {
        val newFn = (arg1: A1, arg2: A2, arg3: A3, arg4: A4) => fn(arg1, arg2, arg3, arg4).provide(env)
        serviceFactory(newFn)
      })
      .toLayer
  }

  def toZIO: ZIO[R, E, (A1, A2, A3, A4) => ZIO[Any, E, V]] = {
    ZIO
      .access[R](env => {
        (arg1: A1, arg2: A2, arg3: A3, arg4: A4) => fn(arg1, arg2, arg3, arg4).provide(env)
      })
  }
}

object ZRunnable {
  def apply[A, R, E, V](fn: A => ZIO[R, E, V]): ZRunnable1[A, R, E, V] = new ZRunnable1[A, R, E, V](fn)
  def apply[A1, A2, R, E, V](fn: (A1, A2) => ZIO[R, E, V]): ZRunnable2[A1, A2, R, E, V] =
    new ZRunnable2[A1, A2, R, E, V](fn)
  def apply[A1, A2, A3, R, E, V](fn: (A1, A2, A3) => ZIO[R, E, V]): ZRunnable3[A1, A2, A3, R, E, V] =
    new ZRunnable3[A1, A2, A3, R, E, V](fn)
  def apply[A1, A2, A3, A4, R, E, V](fn: (A1, A2, A3, A4) => ZIO[R, E, V]): ZRunnable4[A1, A2, A3, A4, R, E, V] =
    new ZRunnable4[A1, A2, A3, A4, R, E, V](fn)
}
