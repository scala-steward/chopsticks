package dev.chopsticks.fp

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.{ActorMaterializer, Attributes, KillSwitches, Materializer}
import dev.chopsticks.fp.ZAkka.ops._
import dev.chopsticks.testkit.ManualTimeAkkaTestKit.ManualClock
import dev.chopsticks.testkit.{AkkaTestKitAutoShutDown, FixedMockClockService, ManualTimeAkkaTestKit}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Assertion, AsyncWordSpecLike, Matchers, Succeeded}
import zio.blocking._
import zio.clock.Clock
import zio.test.mock.MockClock
import zio.{Task, UIO, ZIO}

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

final class ZAkkaTest
    extends ManualTimeAkkaTestKit
    with AsyncWordSpecLike
    with Matchers
    with AkkaTestKitAutoShutDown
    with ScalaFutures {

  implicit val mat: Materializer = ActorMaterializer()

  type Env = AkkaEnv with MockClock with Blocking

  private def createEnv: Env = {
    new AkkaEnv with MockClock with Blocking.Live {
      implicit val actorSystem: ActorSystem = system
      private val fixedMockClockService = FixedMockClockService(unsafeRun(MockClock.makeMock(MockClock.DefaultData)))
      val clock: MockClock.Service[Any] = fixedMockClockService
      val scheduler: MockClock.Service[Any] = fixedMockClockService
    }
  }

  def withEnv(test: Env => Assertion): Future[Assertion] = {
    Future(test(createEnv))
  }

  def withEffect[E, A](test: ZIO[Env, Throwable, Assertion]): Future[Assertion] = {
    val env = createEnv
    env.unsafeRunToFuture(test.provide(env))
  }

  "interruptableLazySource" should {
    "interrupt effect" in withEffect {
      for {
        clock <- ZIO.access[MockClock](c => new ManualClock(Some(c)))
        startP <- zio.Promise.make[Nothing, Unit]
        startFuture <- startP.await.toFuture
        interruptedP <- zio.Promise.make[Nothing, Unit]
        interruptedFuture <- interruptedP.await.toFuture
        source <- ZAkka.interruptableLazySource {
          startP.succeed(()) *> ZIO.succeed(1)
            .delay(zio.duration.Duration(3, TimeUnit.SECONDS))
            .onInterrupt(interruptedP.succeed(()))
        }
        _ <- blocking(Task {
          val ks = source
            .viaMat(KillSwitches.single)(Keep.right)
            .toMat(Sink.ignore)(Keep.left)
            .run

          whenReady(startFuture)(identity)
          ks.shutdown()
          clock.timePasses(3.seconds)
          whenReady(interruptedFuture)(identity)
        })
      } yield Succeeded
    }
  }

  "manual time" in withEnv { implicit env =>
    val clock = new ManualClock(Some(env))

    val (source, sink) = TestSource
      .probe[Int]
      .effectMapAsync(1) { i =>
        ZIO.accessM[Clock] { env =>
          val c = env.clock
          c.sleep(zio.duration.Duration(10, TimeUnit.SECONDS)) *> UIO(i + 1)
        }
      }
      .withAttributes(Attributes.inputBuffer(initial = 0, max = 1))
      .toMat(TestSink.probe[Int])(Keep.both)
      .run

    sink.request(2)
    source.sendNext(1)
    source.sendNext(2)
    sink.ensureSubscription()
    sink.expectNoMessage(100.millis)
    clock.timePasses(9.seconds)
    sink.expectNoMessage(100.millis)
    clock.timePasses(1.second)
    sink.expectNext(2)
    sink.expectNoMessage(100.millis)
    clock.timePasses(10.seconds)
    sink.expectNext(3)

    source.sendComplete()
    sink.expectComplete()

    Succeeded
  }

  "ops" when {
    "switchFlatMapConcat" should {
      "function identically to flatMapConcat when there's no switching" in withEnv { implicit env =>
        val (source, sink) = TestSource
          .probe[Source[Int, Any]]
          .switchFlatMapConcat(UIO(_))
          .toMat(TestSink.probe)(Keep.both)
          .run

        source.sendNext(Source(1 to 3))
        sink.request(3)
        sink.expectNextN(Vector(1, 2, 3))

        source.sendNext(Source(4 to 6))
        sink.request(3)
        sink.expectNextN(Vector(4, 5, 6))

        source.sendComplete()
        sink.expectComplete()

        Succeeded
      }

      "cancel the prior source and switch to the new one" in withEnv { implicit env =>
        import env.dispatcher
//        val clock = new ManualClock(Some(env))
        val promise = Promise[Boolean]()
        val (source, sink) = TestSource
          .probe[Source[Int, Any]]
          .switchFlatMapConcat(UIO(_))
          .toMat(TestSink.probe)(Keep.both)
          .run

        sink.request(2)
        source.sendNext {
          Source
            .fromFuture(akka.pattern.after(3.seconds, env.actorSystem.scheduler)(Future.successful(1)))
            .watchTermination() { (_, f) =>
              f.onComplete(_ => promise.success(true))
              f
            }
        }

        source.sendNext(Source.single(2))
        sink.expectNext(2)

        whenReady(promise.future) { r =>
          r should be(true)
        }

        source.sendComplete()
        sink.expectComplete()

        Succeeded
      }
    }
  }

}
