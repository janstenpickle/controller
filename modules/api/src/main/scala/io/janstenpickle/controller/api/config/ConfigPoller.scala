package io.janstenpickle.controller.api.config

import java.nio.file.Path

import cats.Eq
import cats.effect.{Concurrent, Resource, Timer}
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import cats.instances.all._
import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosInt
import extruder.cats.effect.EffectValidation
import extruder.core.ExtruderErrors
import extruder.data.ValidationErrors
import io.janstenpickle.controller.poller.DataPoller.Data
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object ConfigPoller {
  private val log = LoggerFactory.getLogger(getClass)

  implicit val pathEq: Eq[Path] = Eq.by(_.toString)
  implicit val macEq: Eq[Mac] = Eq.by(_.getMacString)
  implicit val configEq: Eq[Configuration.Config] = cats.derived.semi.eq[Configuration.Config]

  def apply[F[_]: Timer: ExtruderErrors](
    onUpdate: Either[ValidationErrors, Configuration.Config] => F[Unit]
  )(implicit F: Concurrent[F]): Resource[F, () => F[Either[ValidationErrors, Configuration.Config]]] = {
    type ConfigResult[A] = EffectValidation[F, A]

    Resource.liftF(Configuration.load[ConfigResult].value).flatMap { initial =>
      implicit val empty: Empty[Either[ValidationErrors, Configuration.Config]] = Empty(initial)

      // val x: (Data[Either[ValidationErrors, Configuration.Config]], Throwable) => F[Unit] = ???

      DataPoller[F, Either[ValidationErrors, Configuration.Config], () => F[
        Either[ValidationErrors, Configuration.Config]
      ]](
        (_: Data[Either[ValidationErrors, Configuration.Config]]) => Configuration.load[ConfigResult].value,
        1.minute,
        PosInt(1),
        onUpdate
      ) { (get, _) =>
        get
      }
    }
  }
}
