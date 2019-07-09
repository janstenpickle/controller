package io.janstenpickle.controller.store.file

import java.io.File
import java.nio.file.{Files, Path, Paths}

import cats.{~>, Eq, Parallel}
import cats.derived.semi
import cats.effect.concurrent.Semaphore
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.instances.list._
import cats.instances.map._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.model._
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.store.trace.TracedSwitchStateStore
import natchez.Trace
import natchez.TraceValue.NumberValue
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object FileSwitchStateStore {
  case class Config(location: Path, timeout: FiniteDuration = 1.second)
  case class PollingConfig(pollInterval: FiniteDuration = 5.seconds, errorCount: PosInt = PosInt(3))

  private case class StateKey(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString)

  private implicit val stateKeyEq: Eq[StateKey] = semi.eq

  def apply[F[_]: Concurrent: ContextShift: Timer: Trace](config: Config, blocker: Blocker): F[SwitchStateStore[F]] =
    Semaphore[F](1).map { semaphore =>
      def file(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): Path =
        Paths.get(config.location.toString, remote.value, device.value, name.value)

      def writeState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString, state: State): F[Unit] = {
        val f = file(remote, device, name)
        blocker.blockOn(for {
          _ <- Concurrent.timeout(semaphore.acquire, config.timeout)
          _ <- Sync[F].delay(FileUtils.forceMkdirParent(f.toFile))
          _ <- Sync[F].delay(Files.write(f, state.value.getBytes))
          _ <- semaphore.release
        } yield ())
      }

      TracedSwitchStateStore(
        new SwitchStateStore[F] {
          override def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
            writeState(remote, device, name, State.On)

          override def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
            writeState(remote, device, name, State.Off)

          override def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State] =
            blocker
              .delay(new String(Files.readAllBytes(file(remote, device, name))))
              .map(_.trim.toLowerCase match {
                case "on" => State.On
                case _ => State.Off
              })
        },
        fileStoreType,
        "path" -> config.location.toString,
        "timeout" -> NumberValue(config.timeout.toMillis)
      )
    }

  def polling[F[_]: ContextShift: Timer: Parallel: Trace, G[_]: Concurrent: Timer](
    config: Config,
    pollingConfig: PollingConfig,
    blocker: Blocker,
    onUpdate: Map[StateKey, State] => F[Unit]
  )(implicit F: Concurrent[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, SwitchStateStore[F]] =
    Resource.liftF(apply[F](config, blocker)).flatMap { underlying =>
      def states: F[Map[StateKey, State]] =
        blocker
          .delay[F, List[(File, File, File)]](for {
            remote <- Option(config.location.toFile.listFiles()).toList.flatten
            device <- Option(remote.listFiles()).toList.flatten
            name <- Option(device.listFiles()).toList.flatten
          } yield (remote, device, name))
          .flatMap { states =>
            states
              .parTraverse {
                case (remote, device, name) =>
                  F.fromEither((for {
                      r <- refineV[NonEmpty](remote.getName)
                      d <- refineV[NonEmpty](device.getName)
                      n <- refineV[NonEmpty](name.getName)
                    } yield (r, d, n)).leftMap(new RuntimeException(_) with NoStackTrace))
                    .flatMap {
                      case (r, d, n) =>
                        underlying.getState(r, d, n).map(StateKey(r, d, n) -> _)
                    }
              }
              .map(_.toMap)
          }

      DataPoller.traced[F, G, Map[StateKey, State], SwitchStateStore[F]]("switchState")(
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
