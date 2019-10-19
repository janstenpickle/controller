package io.janstenpickle.controller.configsource.extruder

import cats.data.NonEmptyList
import cats.effect._
import cats.instances.string._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import extruder.cats.effect.EffectValidation
import extruder.core.{Decoder, Encoder, Parser, Settings, Show}
import extruder.refined._
import extruder.typesafe.instances._
import extruder.typesafe.IntermediateTypes.Config
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommand}
import natchez.Trace

object ExtruderRemoteCommandConfigSource {
  implicit val remoteCommandParser: Parser[RemoteCommand] = Parser[String].flatMapResult { value =>
    value.split(KeySeparator).toList match {
      case remote :: device :: name :: Nil =>
        for {
          r <- refineV[NonEmpty](remote)
          d <- refineV[NonEmpty](device)
          n <- refineV[NonEmpty](name)
        } yield RemoteCommand(r, d, n)
      case _ => Left(s"Invalid remote command value '$value'")
    }
  }
  implicit val remoteCommandShow: Show[RemoteCommand] = Show { rc =>
    s"${rc.remote}$KeySeparator${rc.device}$KeySeparator${rc.name}"
  }
  implicit val commandPayloadParser: Parser[CommandPayload] = Parser[String].map(CommandPayload(_))
  implicit val commandPayloadShow: Show[CommandPayload] = Show(_.hexValue)

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: ConfigResult[RemoteCommand, CommandPayload] => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, RemoteCommand, CommandPayload]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[RemoteCommand, CommandPayload], TConfig] =
      Decoder[EV, Settings, ConfigResult[RemoteCommand, CommandPayload], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[RemoteCommand, CommandPayload], Config] =
      Encoder[F, Settings, ConfigResult[RemoteCommand, CommandPayload], Config]

    ExtruderConfigSource
      .polling[F, G, RemoteCommand, CommandPayload]("remoteCommand", pollingConfig, config, onUpdate, decoder, encoder)
  }
}
