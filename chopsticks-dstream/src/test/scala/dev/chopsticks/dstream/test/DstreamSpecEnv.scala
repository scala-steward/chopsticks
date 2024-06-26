package dev.chopsticks.dstream.test

import org.apache.pekko.NotUsed
import dev.chopsticks.dstream.DstreamServerHandlerFactory.DstreamServerPartialHandler
import dev.chopsticks.dstream.metric.DstreamWorkerMetrics.DstreamWorkerMetric
import dev.chopsticks.dstream.metric.DstreamMasterMetrics.DstreamMasterMetric
import dev.chopsticks.dstream.metric.DstreamStateMetrics.DstreamStateMetric
import dev.chopsticks.dstream.test.DstreamTestUtils.ToTestZLayer
import dev.chopsticks.dstream.test.proto.{
  Assignment,
  DstreamSampleService,
  DstreamSampleServiceClient,
  DstreamSampleServicePowerApiHandler,
  Result
}
import dev.chopsticks.dstream._
import dev.chopsticks.dstream.metric.{
  DstreamClientMetricsManager,
  DstreamMasterMetricsManager,
  DstreamStateMetricsManager
}
import dev.chopsticks.fp.pekko_env.PekkoEnv
import dev.chopsticks.fp.config.HoconConfig
import dev.chopsticks.fp.iz_logging.{IzLogging, IzLoggingRouter}
import dev.chopsticks.metric.prom.PromMetricRegistryFactory
import io.prometheus.client.CollectorRegistry
import zio.{ZIO, ZLayer}

//noinspection TypeAnnotation
object DstreamSpecEnv {
  lazy val hoconConfigLayer = HoconConfig.live(None).forTest
  lazy val izLoggingRouterLayer = IzLoggingRouter.live.forTest
  lazy val izLoggingLayer = IzLogging.live().forTest
  lazy val PekkoEnvLayer = PekkoEnv.live("test").forTest

  type SharedEnv = HoconConfig with IzLogging with PekkoEnv
}

//noinspection TypeAnnotation
trait DstreamSpecEnv {
  import DstreamSpecEnv._

  protected lazy val sharedLayer = ZLayer.make[SharedEnv](
    hoconConfigLayer,
    izLoggingRouterLayer,
    izLoggingLayer,
    PekkoEnvLayer
  )

  protected lazy val promRegistryLayer = ZLayer.succeed(CollectorRegistry.defaultRegistry).forTest
  protected lazy val stateMetricRegistryFactoryLayer =
    PromMetricRegistryFactory.live[DstreamStateMetric]("test").forTest
  protected lazy val clientMetricRegistryFactoryLayer =
    PromMetricRegistryFactory.live[DstreamWorkerMetric]("test").forTest
  protected lazy val masterMetricRegistryFactoryLayer =
    PromMetricRegistryFactory.live[DstreamMasterMetric]("test").forTest

  protected lazy val dstreamStateMetricsManagerLayer = DstreamStateMetricsManager.live.forTest
  protected lazy val dstreamClientMetricsManagerLayer = DstreamClientMetricsManager.live.forTest
  protected lazy val dstreamMasterMetricsManagerLayer = DstreamMasterMetricsManager.live.forTest

  protected lazy val dstreamStateLayer = ZLayer.scoped(DstreamState.manage[Assignment, Result]("test")).forTest
  protected lazy val dstreamServerHandlerFactoryLayer = DstreamServerHandlerFactory.live[Assignment, Result] { handle =>
    ZIO
      .serviceWith[PekkoEnv](_.actorSystem)
      .map { implicit as =>
        DstreamServerPartialHandler(
          DstreamSampleServicePowerApiHandler.partial(handle(_, _)),
          DstreamSampleService
        )
      }
  }.forTest
  protected lazy val dstreamServerHandlerLayer = DstreamServerHandler.live[Assignment, Result].forTest
  protected lazy val dstreamClientLayer = DstreamClient
    .live[Assignment, Result] { settings =>
      ZIO
        .serviceWith[PekkoEnv](_.actorSystem)
        .map { implicit as =>
          DstreamSampleServiceClient(settings)
        }
    } { (client, _) =>
      client.work()
    }.forTest

  protected lazy val dstreamServerLayer = DstreamServer.live[Assignment, Result].forTest
  protected lazy val dstreamMasterLayer = DstreamMaster.live[Assignment, Assignment, Result, Assignment].forTest
  protected lazy val dstreamWorkerLayer = DstreamWorker.live[Assignment, Result, NotUsed].forTest
}
