package io.janstenpickle.controller.plugin.api

import cats.Parallel
import cats.effect.{Concurrent, Timer}
import cats.mtl.ApplicativeHandle
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.{Macro, MacroErrors}
import io.janstenpickle.controller.api.endpoint._
import io.janstenpickle.controller.api.service.{ConfigService, ConfigServiceErrors}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.events.Events
import io.janstenpickle.controller.http4s.error.ControlError
import io.janstenpickle.controller.model.{Button, Room}
import io.janstenpickle.controller.switch.{SwitchErrors, Switches}
import natchez.Trace
import org.http4s.HttpRoutes
import org.http4s.server.Router

object PluginApi {
  def apply[F[_]: Concurrent: Parallel: Timer: Trace: ConfigServiceErrors: SwitchErrors: MacroErrors: ApplicativeHandle[
    *[_],
    ControlError
  ], G[_]: Concurrent: Timer](events: Events[F], components: Components[F], switches: Switches[F], mac: Macro[F])(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): F[HttpRoutes[F]] =
    ConfigService[F](
      components.activityConfig,
      ConfigSource.empty[F, String, Button],
      components.remoteConfig,
      components.macroConfig,
      ConfigSource.empty[F, Room, NonEmptyString],
      switches
    ).map { configService =>
      Router(
        "/control/remote" -> new RemoteApi[F](components.remotes).routes,
        "/control/switch" -> new SwitchApi[F](switches).routes,
        "/control/macro" -> new MacroApi[F](mac).routes,
        "/command" -> new CommandWs[F, G](events.command.publisher.mapK(liftLower.lower, liftLower.lift)).routes,
        "/config" -> new ConfigApi[F, G](configService, events.activity, events.config, events.switch).routes,
        "/discovery" -> new RenameApi[F](components.rename).routes,
        "/schedule" -> new ScheduleApi[F](components.scheduler).routes,
      )
    }
}
