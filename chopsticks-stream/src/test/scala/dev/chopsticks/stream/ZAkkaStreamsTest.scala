package dev.chopsticks.stream

import java.util.concurrent.TimeUnit

import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.{ActorMaterializer, Attributes, KillSwitches, Materializer}
import dev.chopsticks.fp.{AkkaApp, AkkaEnv, LogEnv}
import dev.chopsticks.stream.ZAkkaStreams.ops._
import dev.chopsticks.testkit.ManualTimeAkkaTestKit.ManualClock
import dev.chopsticks.testkit.{AkkaTestKitAutoShutDown, FixedTestClockService, ManualTimeAkkaTestKit}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Assertion, AsyncWordSpecLike, Matchers, Succeeded}
import zio.blocking._
import zio.clock.Clock
import zio.internal.PlatformLive
import zio.test.environment.TestClock
import zio.{Task, UIO, ZIO}

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

final class ZAkkaStreamsTest
    extends ManualTimeAkkaTestKit
    with AsyncWordSpecLike
    with Matchers
    with AkkaTestKitAutoShutDown
    with ScalaFutures {

  implicit val mat: Materializer = ActorMaterializer()

  type Env = AkkaEnv with TestClock with Blocking

  private def createRuntime: zio.Runtime[Env] = {
    AkkaApp.createRuntime(new AkkaEnv with LogEnv.Live with TestClock with Blocking.Live {
      val akkaService: AkkaEnv.Service = AkkaEnv.Service.Live(system)
      private val fixedTestClockService = FixedTestClockService(
        zio.Runtime[Unit]((), PlatformLive.fromExecutionContext(akkaService.dispatcher))
          .unsafeRun(TestClock.makeTest(TestClock.DefaultData))
      )
      val clock: TestClock.Service[Any] = fixedTestClockService
      val scheduler: TestClock.Service[Any] = fixedTestClockService
    })
  }


  def withRuntime(test: zio.Runtime[Env] => Assertion): Future[Assertion] = {
    Future(test(createRuntime))
  }

  def withEffect[E, A](test: ZIO[Env, Throwable, Assertion]): Future[Assertion] = {
    val rt = createRuntime
    rt.unsafeRunToFuture(test.provide(rt.Environment))
  }

  "interruptableLazySource" should {
    "interrupt effect" in withEffect {
      for {
        clock <- ZIO.access[TestClock](c => new ManualClock(Some(c)))
        startP <- zio.Promise.make[Nothing, Unit]
        startFuture <- startP.await.toFuture
        interruptedP <- zio.Promise.make[Nothing, Unit]
        interruptedFuture <- interruptedP.await.toFuture
        source <- ZAkkaStreams.interruptableLazySource {
          startP.succeed(()) *> ZIO
            .succeed(1)
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

  "manual time" in withRuntime { implicit rt =>
    val clock = new ManualClock(Some(rt.Environment))

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

  "ZAkkaStreams" when {
    "recursiveSource" should {
      "repeat" in withRuntime { implicit env =>
        val sink = ZAkkaStreams
          .recursiveSource(ZIO.succeed(1), (_: Int, o: Int) => o) { s =>
            Source(s to s + 2)
          }
          .toMat(TestSink.probe[Int])(Keep.right)
          .run()

        val sub = sink.expectSubscription()
        sub.request(6)
        sink.expectNextN(List(1, 2, 3, 3, 4, 5))
        sub.cancel()

        Succeeded
      }
    }
  }

  "ops" when {
    "switchFlatMapConcat" should {
      "function identically to flatMapConcat when there's no switching" in withRuntime { implicit env =>
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

      "cancel the prior source and switch to the new one" in withRuntime { implicit rt =>
        val akkaService = rt.Environment.akkaService

        val promise = Promise[Boolean]()
        val (source, sink) = TestSource
          .probe[Source[Int, Any]]
          .switchFlatMapConcat(UIO(_))
          .toMat(TestSink.probe)(Keep.both)
          .run

        sink.request(2)
        source.sendNext {
          Source
            .fromFuture(
              akka.pattern.after(3.seconds, akkaService.actorSystem.scheduler)(Future.successful(1))(akkaService.dispatcher)
            )
            .watchTermination() { (_, f) =>
              f.onComplete(_ => promise.success(true))(akkaService.dispatcher)
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