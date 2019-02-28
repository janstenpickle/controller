package io.janstenpickle.controller.sonos

import cats.effect._
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import com.vmichalak.sonoscontroller
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait SonosDiscovery[F[_]] {
  def devices: F[Map[NonEmptyString, SonosDevice[F]]]
}

object SonosDiscovery {
  case class Polling(pollInterval: FiniteDuration = 5.seconds, errorCount: PosInt = PosInt(3))

  def apply[F[_]: ContextShift](ec: ExecutionContext)(implicit F: Sync[F]): SonosDiscovery[F] = {
    def suspendErrorsEval[A](thunk: => A): F[A] = suspendErrorsEvalOn(thunk, ec)

    def snakify(name: String) =
      name
        .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
        .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
        .replaceAll("\\s+", "_")
        .toLowerCase

    new SonosDiscovery[F] {
      override def devices: F[Map[NonEmptyString, SonosDevice[F]]] =
        for {
          discovered <- suspendErrorsEval(sonoscontroller.SonosDiscovery.discover().asScala.toList)
          devices <- discovered.traverse { device =>
            for {
              name <- suspendErrorsEval(device.getZoneName)
              formattedName <- F.fromEither(NonEmptyString.from(snakify(name)).leftMap(new RuntimeException(_)))
              nonEmptyName <- F.fromEither(NonEmptyString.from(name).leftMap(new RuntimeException(_)))
            } yield formattedName -> SonosDevice[F](formattedName, nonEmptyName, device, ec)
          }
        } yield devices.toMap
    }
  }

  def polling[F[_]: Concurrent: ContextShift: Timer](
    config: Polling,
    ec: ExecutionContext
  ): Resource[F, SonosDiscovery[F]] = {
    val discovery = SonosDiscovery[F](ec)

    DataPoller[F, Map[NonEmptyString, SonosDevice[F]], SonosDiscovery[F]](
      (_: Data[Map[NonEmptyString, SonosDevice[F]]]) => discovery.devices,
      config.pollInterval,
      config.errorCount
    ) { (getData, _) =>
      new SonosDiscovery[F] {
        override def devices: F[Map[NonEmptyString, SonosDevice[F]]] = getData()
      }
    }
  }
}
