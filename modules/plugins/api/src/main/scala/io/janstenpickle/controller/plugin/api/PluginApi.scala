package io.janstenpickle.controller.plugin.api

import cats.Parallel
import cats.effect.MonadCancelThrow
import cats.effect.kernel.Async
import cats.mtl.ApplicativeHandle
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.{Macro, MacroErrors}
import io.janstenpickle.controller.api.endpoint._
import io.janstenpickle.controller.api.service.{ConfigService, ConfigServiceErrors}
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.events.Events
import io.janstenpickle.controller.http4s.error.ControlError
import io.janstenpickle.controller.model.{Button, Room}
import io.janstenpickle.controller.switch.{SwitchErrors, Switches}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import org.http4s.HttpRoutes
import org.http4s.server.Router

object PluginApi {
  def apply[F[_]: Async: Parallel: Trace: ConfigServiceErrors: SwitchErrors: MacroErrors: ApplicativeHandle[
    *[_],
    ControlError
  ], G[_]: MonadCancelThrow](
    events: Events[F],
    components: Components[F],
    switches: Switches[F],
    mac: Macro[F],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): F[HttpRoutes[F]] =
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
        "/config" -> new ConfigApi[F, G](configService, events.activity, events.config, events.switch, k).routes,
        "/discovery" -> new RenameApi[F](components.rename).routes,
        "/schedule" -> new ScheduleApi[F](components.scheduler).routes,
      )
    }
}
