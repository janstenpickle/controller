package io.janstenpickle.controller.configsource.extruder

import cats.data.NonEmptyList
import cats.effect._
import cats.instances.string._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.{refineMV, refineV}
import extruder.cats.effect.EffectValidation
import extruder.core.{Decoder, Encoder, Parser, Settings, Show}
import extruder.refined._
import extruder.typesafe.instances._
import extruder.typesafe.IntermediateTypes.Config
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommandKey}
import natchez.Trace

object ExtruderRemoteCommandConfigSource {
  implicit val remoteCommandParser: Parser[RemoteCommandKey] = Parser[String].flatMapResult { value =>
    value.split(KeySeparator).toList match {
      case device :: name :: Nil =>
        for {
          d <- refineV[NonEmpty](device)
          n <- refineV[NonEmpty](name)
        } yield RemoteCommandKey(None, d, n)
      case _ => Left(s"Invalid remote command value '$value'")
    }
  }
  implicit val remoteCommandShow: Show[RemoteCommandKey] = Show { rc =>
    s"${rc.device}$KeySeparator${rc.name}"
  }
  implicit val commandPayloadParser: Parser[CommandPayload] = Parser[String].map(CommandPayload(_))
  implicit val commandPayloadShow: Show[CommandPayload] = Show(_.hexValue)

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: ConfigResult[RemoteCommandKey, CommandPayload] => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, RemoteCommandKey, CommandPayload]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[RemoteCommandKey, CommandPayload], TConfig] =
      Decoder[EV, Settings, ConfigResult[RemoteCommandKey, CommandPayload], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[RemoteCommandKey, CommandPayload], Config] =
      Encoder[F, Settings, ConfigResult[RemoteCommandKey, CommandPayload], Config]

    ExtruderConfigSource
      .polling[F, G, RemoteCommandKey, CommandPayload](
        "remoteCommand",
        pollingConfig,
        config,
        onUpdate,
        decoder,
        encoder
      )
  }
}
