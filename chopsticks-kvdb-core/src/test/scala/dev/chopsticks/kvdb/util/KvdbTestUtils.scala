package dev.chopsticks.kvdb.util

import better.files.File
import dev.chopsticks.fp.AkkaDiApp
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.zio_ext._
import dev.chopsticks.kvdb.TestDatabase
import dev.chopsticks.kvdb.TestDatabase.BaseCf
import org.scalatest.Assertion
import zio.blocking.{blocking, Blocking}
import zio.{RIO, Task, TaskLayer, ZManaged}

import scala.concurrent.Future

object KvdbTestUtils {
  def managedTempDir: ZManaged[Blocking, Throwable, File] = {
    ZManaged.make {
      blocking(Task(File.newTemporaryDirectory().deleteOnExit()))
    } { f => blocking(Task(f.delete())).orDie }
  }

  def createTestRunner[R <: AkkaDiApp.Env with IzLogging, Db](
    managed: ZManaged[R, Throwable, Db],
    layer: TaskLayer[R]
  )(implicit
    rt: zio.Runtime[Any]
  ): (Db => RIO[R, Assertion]) => Future[Assertion] = {
    (testCode: Db => RIO[R, Assertion]) =>
      managed
        .use(testCode(_).interruptAllChildrenPar)
        .provideLayer(layer)
        .unsafeRunToFuture
  }

  def populateColumn[CF <: BaseCf[K, V], K, V](
    db: TestDatabase.Db,
    column: CF,
    pairs: Seq[(K, V)]
  ): RIO[MeasuredLogging, Unit] = {
    val batch = pairs
      .foldLeft(db.transactionBuilder()) {
        case (tx, (k, v)) =>
          tx.put(column, k, v)
      }
      .result
    db.transactionTask(batch)
  }
}
