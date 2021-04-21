package dev.chopsticks.kvdb.api

import dev.chopsticks.fp.iz_logging.{IzLogging, IzLoggingRouter}
import dev.chopsticks.fp.AkkaDiApp
import dev.chopsticks.kvdb.TestDatabase
import dev.chopsticks.kvdb.TestDatabase.DbApi
import dev.chopsticks.kvdb.util.{KvdbIoThreadPool, KvdbTestUtils}
import dev.chopsticks.testkit.{AkkaTestKit, AkkaTestKitAutoShutDown}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike
import zio.ZManaged

abstract class KvdbDatabaseApiTest
    extends AkkaTestKit
    with AsyncWordSpecLike
    with Matchers
    with AkkaTestKitAutoShutDown {
  protected def managedDb: ZManaged[AkkaDiApp.Env with KvdbIoThreadPool, Throwable, DbApi]
  protected def dbMat: TestDatabase.Materialization
//  protected def anotherCf: AnotherCf1

  private lazy val runtime = AkkaDiApp.createRuntime(AkkaDiApp.Env.live ++ (IzLoggingRouter.live >>> IzLogging.live(
    typesafeConfig,
    "iz-logging"
  )))
  private lazy val runtimeLayer = AkkaDiApp.Env.live >+> KvdbIoThreadPool.live()
  private lazy val withDb = KvdbTestUtils.createTestRunner(managedDb, runtimeLayer)(runtime)
  private lazy val withCf = KvdbTestUtils.createTestRunner(
    managedDb.map(_.columnFamily(dbMat.plain)),
    runtimeLayer
  )(runtime)

//  "open" should {
//    "work" in withDb { db =>
//      db.openTask()
//        .map(_ => assert(true))
//    }
//  }

  "columnFamily" when {
    "getTask" should {
      "work with db" in withDb { db =>
        val cf = db.columnFamily(dbMat.plain)
        for {
          _ <- cf.putTask("foo", "bar")
          v <- cf.getValueTask(_ is "foo")
        } yield {
          v should equal(Some("bar"))
        }
      }

      "work with cf" in withCf { cf =>
        for {
          _ <- cf.putTask("foo", "bar")
          v <- cf.getValueTask(_ is "foo")
        } yield {
          v should equal(Some("bar"))
        }
      }
    }
  }
}
