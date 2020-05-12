package dev.chopsticks.sample.app

import dev.chopsticks.sample.app.AkkaDistageTestApp.Bar.BarService
import dev.chopsticks.sample.app.AkkaDistageTestApp.{Bar, Foo}
import dev.chopsticks.sample.app.AkkaDistageTestApp.Foo.FooService
import distage.{Injector, ModuleDef}
import izumi.distage.model.plan.GCMode
import zio.{Has, Task, ZIO, ZLayer}
import zio.clock.Clock

object DistageTest {
  def main(args: Array[String]): Unit = {
    @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
    val definition = new ModuleDef {
      make[Clock.Service].fromHas(Clock.live)
      make[Foo.Service].fromHas(ZLayer.succeed(FooService("foo")))
      make[Bar.Service].fromHas(ZLayer.succeed(BarService("foo")))
    }

    val plan = Injector().plan(definition, GCMode.NoGC)
    println(plan.render())
    plan.assertImportsResolvedOrThrow

    val _ = Injector()
      .produceGetF[Task, Has[Foo.Service] with Has[Bar.Service]](definition).toZIO
      .use { _ =>
        ZIO.unit
      }
  }
}
