package io.janstenpickle.controller.store.file

import java.nio.file.{Files, Path, Paths}

import cats.Eq
import cats.derived.semi
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.instances.list._
import cats.instances.map._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.model._
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.store.SwitchStateStore
import org.apache.commons.io.FileUtils

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object FileSwitchStateStore {
  case class Config(location: Path, timeout: FiniteDuration = 1.second)
  case class PollingConfig(pollInterval: FiniteDuration = 5.seconds, errorCount: PosInt = PosInt(3))

  private case class StateKey(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString)

  private implicit val stateKeyEq: Eq[StateKey] = semi.eq

  def apply[F[_]: Concurrent: ContextShift: Timer](config: Config, ec: ExecutionContext): F[SwitchStateStore[F]] =
    Semaphore[F](1).map { semaphore =>
      def file(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): Path =
        Paths.get(config.location.toString, remote.value, device.value, name.value)

      def writeState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString, state: State): F[Unit] = {
        val f = file(remote, device, name)
        evalOn(for {
          _ <- Concurrent.timeout(semaphore.acquire, config.timeout)
          _ <- suspendErrors(FileUtils.forceMkdirParent(f.toFile))
          _ <- suspendErrors(Files.write(f, state.value.getBytes))
          _ <- semaphore.release
        } yield (), ec)
      }

      new SwitchStateStore[F] {
        override def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
          writeState(remote, device, name, State.On)

        override def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
          writeState(remote, device, name, State.Off)

        override def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State] =
          suspendErrorsEvalOn(new String(Files.readAllBytes(file(remote, device, name))), ec)
            .map(_.trim.toLowerCase match {
              case "on" => State.On
              case _ => State.Off
            })
      }
    }

  def polling[F[_]: ContextShift: Timer](
    config: Config,
    pollingConfig: PollingConfig,
    ec: ExecutionContext,
    onUpdate: Map[StateKey, State] => F[Unit]
  )(implicit F: Concurrent[F]): Resource[F, SwitchStateStore[F]] = {
    def suspendErrorsEval[A](thunk: => A): F[A] = suspendErrorsEvalOn(thunk, ec)

    Resource.liftF(apply[F](config, ec)).flatMap { underlying =>
      def states: F[Map[StateKey, State]] =
        suspendErrorsEval(for {
          remote <- Option(config.location.toFile.listFiles()).toList.flatten
          device <- Option(remote.listFiles()).toList.flatten
          name <- Option(device.listFiles()).toList.flatten
        } yield (remote, device, name))
          .flatMap { states =>
            states
              .traverse {
                case (remote, device, name) =>
                  F.fromEither((for {
                      r <- refineV[NonEmpty](remote.getName)
                      d <- refineV[NonEmpty](device.getName)
                      n <- refineV[NonEmpty](name.getName)
                    } yield (r, d, n)).leftMap(new RuntimeException(_)))
                    .flatMap {
                      case (r, d, n) =>
                        underlying.getState(r, d, n).map(StateKey(r, d, n) -> _)
                    }
              }
              .map(_.toMap)
          }

      DataPoller[F, Map[StateKey, State], SwitchStateStore[F]](
        (_: Data[Map[StateKey, State]]) => states,
        pollingConfig.pollInterval,
        pollingConfig.errorCount,
        onUpdate
      ) { (getData, update) =>
        new SwitchStateStore[F] {
          override def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
            underlying.setOn(remote, device, name) *> getData().flatMap { data =>
              update(data.updated(StateKey(remote, device, name), State.On))
            }

          override def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
            underlying.setOff(remote, device, name) *> getData().flatMap { data =>
              update(data.updated(StateKey(remote, device, name), State.Off))
            }

          override def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State] =
            getData().map(_.getOrElse(StateKey(remote, device, name), State.Off))
        }
      }
    }
  }
}
