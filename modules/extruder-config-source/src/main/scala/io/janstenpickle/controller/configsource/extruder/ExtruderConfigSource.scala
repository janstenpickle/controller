package io.janstenpickle.controller.configsource.extruder

import java.nio.file.{Files, Path, Paths}

import cats.effect.{ContextShift, Sync}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.typesafe.config.{ConfigFactory, Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe.yaml.instances._
import extruder.core.ExtruderErrors
import extruder.typesafe._
import extruder.refined._
import io.circe.{parser, Json}
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.{Activity, Button, Remote}

import scala.concurrent.ExecutionContext

object ExtruderConfigSource {
  val Activities = "activities"
  val Remotes = "remotes"
  val Buttons = "buttons"

  case class Config(dir: Path)

  case class Data(typesafe: Option[TConfig], yaml: Option[String], json: Option[Json])

  def apply[F[_]: ContextShift](
    config: Config,
    ec: ExecutionContext
  )(implicit F: Sync[F], errors: ExtruderErrors[F]): ConfigSource[F] = {

    def loadFile(name: String): F[Option[String]] = {
      val file = Paths.get(config.dir.toString, name)
      evalOn(for {
        exists <- suspendErrors(Files.exists(file))
        ret <- if (exists) suspendErrors(new String(Files.readAllBytes(file))).map(Some(_))
        else Option.empty[String].pure

      } yield ret, ec)
    }

    def tConfig(name: String): F[TConfig] = loadFile(s"$name.conf").flatMap {
      case None => ConfigFactory.empty().pure
      case Some(conf) => suspendErrors(ConfigFactory.parseString(conf))
    }

    def yaml(name: String): F[String] = loadFile(s"$name.yaml").map {
      case None => "---"
      case Some(yaml) => yaml
    }

    def json(name: String): F[Json] = loadFile(s"$name.json").flatMap {
      case None => Json.Null.pure
      case Some(json) => F.fromEither(parser.parse(json))
    }

    def configs(name: String): F[((TConfig, String), Json)] =
      for {
        c <- tConfig(name)
        y <- yaml(name)
        j <- json(name)
      } yield ((c, y), j)

    new ConfigSource[F] {
      override def getActivities: F[Map[NonEmptyString, Activity]] =
        configs(Activities)
          .flatMap(
            decodeF[F, List[Activity]]
              .combine(extruder.circe.yaml.datasource)
              .combine(extruder.circe.datasource)(List(Activities), _)
          )
          .map(_.groupBy(_.name).mapValues(_.head))

      override def getRemotes: F[List[Remote]] =
        configs(Remotes).flatMap(
          decodeF[F, List[Remote]]
            .combine(extruder.circe.yaml.datasource)
            .combine(extruder.circe.datasource)(List(Remotes), _)
        )

      override def getCommonButtons: F[List[Button]] =
        configs(Buttons).flatMap(
          decodeF[F, List[Button]]
            .combine(extruder.circe.yaml.datasource)
            .combine(extruder.circe.datasource)(List(Buttons), _)
        )
    }
  }
}
