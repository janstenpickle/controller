package io.janstenpickle.controller.configsource.extruder

import cats.Eq
import cats.effect.{Concurrent, Resource, Sync, Timer}
import com.typesafe.config.Config
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Parser, Settings}
import extruder.data.Validation
import extruder.refined._
import io.circe.{Decoder => CirceDecoder, Json}
import io.janstenpickle.controller.configsource.MultiSwitchConfigSource
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{MultiSwitches, SwitchAction}
import io.janstenpickle.controller.poller.Empty

import scala.concurrent.duration._

object ExtruderMultiSwitchConfigSource {
  implicit val empty: Empty[MultiSwitches] = Empty(MultiSwitches(List.empty, List.empty))

  implicit val switchActionParser: Parser[SwitchAction] = Parser(SwitchAction.fromString(_))

  implicit val switchActionCirceDecoder: CirceDecoder[SwitchAction] =
    CirceDecoder.decodeString.emap(SwitchAction.fromString)

  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  private def mkSource[F[_]](source: () => F[MultiSwitches]): MultiSwitchConfigSource[F] =
    new MultiSwitchConfigSource[F] {
      override def getMultiSwitches: F[MultiSwitches] = source()
    }

  def apply[F[_]: Sync](config: ConfigFileSource[F]): MultiSwitchConfigSource[F] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), MultiSwitches, (Config, Json)] =
      Decoder[EV, (Settings, CirceSettings), MultiSwitches, (Config, Json)]

    val source = ExtruderConfigSource[F, MultiSwitches](
      config,
      (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
      (current, errors) => current.copy(errors = errors.toList.map(_.message))
    )(Sync[F], decoder, empty)

    mkSource[F](source)
  }

  def polling[F[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: MultiSwitches => F[Unit]
  ): Resource[F, MultiSwitchConfigSource[F]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), MultiSwitches, (Config, Json)] =
      Decoder[EV, (Settings, CirceSettings), MultiSwitches, (Config, Json)]

    ExtruderConfigSource
      .polling[F, MultiSwitches](
        pollingConfig.pollInterval,
        config,
        (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
        (current, errors) => current.copy(errors = errors.toList.map(_.message)),
        onUpdate
      )(Timer[F], empty, Eq[MultiSwitches], Concurrent[F], decoder)
      .map(mkSource[F])
  }
}
