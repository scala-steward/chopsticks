package dev.chopsticks.dstream.test

import dev.chopsticks.dstream.DstreamMaster.DstreamMasterConfig
import dev.chopsticks.dstream.DstreamStateMetrics.DstreamStateMetric
import dev.chopsticks.dstream.test.DstreamTestUtils.ToTestZLayer
import dev.chopsticks.dstream.{
  DstreamClient,
  DstreamMaster,
  DstreamServer,
  DstreamServerHandler,
  DstreamServerHandlerFactory,
  DstreamState,
  DstreamStateMetricsManager,
  DstreamWorker
}
import dev.chopsticks.dstream.test.proto.{Assignment, DstreamSampleAppClient, DstreamSampleAppPowerApiHandler, Result}
import dev.chopsticks.fp.akka_env.AkkaEnv
import dev.chopsticks.fp.config.HoconConfig
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.metric.prom.PromMetricRegistryFactory
import io.prometheus.client.CollectorRegistry
import zio.{ZIO, ZLayer}
import eu.timepit.refined.auto._
import zio.magic._

//noinspection TypeAnnotation
object DstreamSpecEnv {
  protected lazy val hoconConfigLayer = HoconConfig.live(None).forTest
  protected lazy val izLoggingLayer = IzLogging.live().forTest
  protected lazy val akkaEnvLayer = AkkaEnv.live("test").forTest

  type SharedEnv = HoconConfig with IzLogging with AkkaEnv
}

//noinspection TypeAnnotation
trait DstreamSpecEnv {
  import DstreamSpecEnv._

  protected lazy val sharedLayer = ZLayer.fromMagic[SharedEnv](hoconConfigLayer, izLoggingLayer, akkaEnvLayer)

  protected lazy val promRegistryLayer = ZLayer.succeed(CollectorRegistry.defaultRegistry).forTest
  protected lazy val promRegistryFactoryLayer = PromMetricRegistryFactory.live[DstreamStateMetric]("test").forTest
  protected lazy val dstreamStateMetricsManagerLayer = DstreamStateMetricsManager.live.forTest
  protected lazy val dstreamStateLayer = DstreamState.manage[Assignment, Result]("test").toLayer.forTest
  protected lazy val dstreamServerHandlerFactoryLayer = DstreamServerHandlerFactory.live[Assignment, Result] { handle =>
    ZIO
      .access[AkkaEnv](_.get.actorSystem)
      .map { implicit as =>
        DstreamSampleAppPowerApiHandler(handle(_, _))
      }
  }.forTest
  protected lazy val dstreamServerHandlerLayer = DstreamServerHandler.live[Assignment, Result].forTest
  protected lazy val dstreamClientLayer = DstreamClient
    .live[Assignment, Result] { settings =>
      ZIO
        .access[AkkaEnv](_.get.actorSystem)
        .map { implicit as =>
          DstreamSampleAppClient(settings)
        }
    } { (client, _) =>
      client.work()
    }.forTest

  protected lazy val dstreamServerLayer = DstreamServer.live[Assignment, Result].forTest
  protected lazy val dstreamMasterLayer = DstreamMaster.live[Assignment, Assignment, Result, Assignment].forTest
  protected lazy val dstreamWorkerLayer = DstreamWorker.live[Assignment, Result]().forTest
  protected lazy val dstreamTestContext =
    DstreamTestUtils.setup[Assignment, Result](DstreamMasterConfig(parallelism = 1, ordered = true)).forTest
}