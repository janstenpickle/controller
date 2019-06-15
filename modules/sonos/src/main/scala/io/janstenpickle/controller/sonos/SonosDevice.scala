package io.janstenpickle.controller.sonos

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, ContextShift, Timer}
import cats.instances.list._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import com.vmichalak.sonoscontroller
import com.vmichalak.sonoscontroller.model.{PlayState, TrackMetadata}
import eu.timepit.refined.types.string.NonEmptyString
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.catseffect.CatsEffect.suspendErrorsEvalOn
import io.janstenpickle.controller.sonos.SonosDevice.DeviceState

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait SonosDevice[F[_]] extends SimpleSonosDevice[F] {
  def id: String
  def refresh: F[Unit]
  def devicesInGroup: F[Set[String]]
  def label: NonEmptyString
  def isPlaying: F[Boolean]
  def nowPlaying: F[Option[NowPlaying]]
  def volume: F[Int]
  def group: F[Unit]
  def unGroup: F[Unit]
  def isGrouped: F[Boolean]
  def getState: F[DeviceState]
}

object SonosDevice {

  case class DeviceState(
    volume: Int,
    isGrouped: Boolean,
    isPlaying: Boolean,
    nowPlaying: Option[NowPlaying],
    isController: Boolean,
    devicesInGroup: Set[String]
  )

  implicit def apply[F[_]: ContextShift: Timer](
    deviceId: String,
    formattedName: NonEmptyString,
    nonEmptyName: NonEmptyString,
    underlying: sonoscontroller.SonosDevice,
    allDevices: Ref[F, Map[String, SonosDevice[F]]],
    commandTimeout: FiniteDuration,
    ec: ExecutionContext,
    onUpdate: () => F[Unit]
  )(implicit F: Concurrent[F]): F[SonosDevice[F]] = {
    def suspendErrorsEval[A](thunk: => A): F[A] = suspendErrorsEvalOn(thunk, ec)

    def _isPlaying: F[Boolean] =
      suspendErrorsEval(underlying.getPlayState).map {
        case PlayState.PLAYING => true
        case PlayState.TRANSITIONING => true
        case _ => false
      }

    def _nowPlaying: F[Option[NowPlaying]] =
      suspendErrorsEval(underlying.getCurrentTrackInfo).map { trackInfo =>
        def element(f: TrackMetadata => String): Option[String] = Option(f(trackInfo.getMetadata)).filterNot(_.isEmpty)

        for {
          track <- element(_.getTitle)
          artist <- element(_.getCreator)
        } yield NowPlaying(track, artist, element(_.getAlbum), element(_.getAlbumArtist), element(_.getAlbumArtURI))
      }

    def refreshState: F[DeviceState] =
      for {
        volume <- suspendErrorsEval(underlying.getVolume)
        isGrouped <- suspendErrorsEval(underlying.isJoined)
        isPlaying <- _isPlaying
        nowPlaying <- _nowPlaying
        isController <- suspendErrorsEval(underlying.isCoordinator)
        devicesInGroup <- suspendErrorsEval(underlying.getZoneGroupState.getZonePlayerUIDInGroup.asScala.toSet)
      } yield DeviceState(volume, isGrouped, isPlaying, nowPlaying, isController, devicesInGroup)

    for {
      logger <- Slf4jLogger.fromName[F](s"Sonos Device ${formattedName.value}")
      initState <- refreshState
      state <- Ref.of(initState)
    } yield
      new SonosDevice[F] {
        override def applicative: Applicative[F] = Applicative[F]
        override def name: NonEmptyString = formattedName
        override def label: NonEmptyString = nonEmptyName

        private def refreshIsPlaying: F[Unit] = _isPlaying.flatMap { np =>
          state.update(_.copy(isPlaying = np))
        }

        override def play: F[Unit] =
          (for {
            grouped <- isGrouped
            controller <- isController
            _ <- if (grouped && !controller)
              doIfController(_.play)
            else suspendErrorsEval(underlying.play())
            _ <- state.update(_.copy(isPlaying = true))
            _ <- onUpdate()
          } yield ())
            .handleErrorWith(logger.error(_)(s"Failed to send play command on Sonos device ${formattedName.value}"))
            .timeoutTo(
              commandTimeout,
              logger.error(s"Timed out sending play command to Sonos device ${formattedName.value}")
            )
            .start
            .void

        override def pause: F[Unit] =
          (for {
            grouped <- isGrouped
            controller <- isController
            _ <- if (grouped && !controller)
              doIfController(_.pause)
            else suspendErrorsEval(underlying.pause())
            _ <- state.update(_.copy(isPlaying = false))
            _ <- onUpdate()
          } yield ())
            .handleErrorWith(logger.error(_)(s"Failed to send pause command on Sonos device ${formattedName.value}"))
            .timeoutTo(
              commandTimeout,
              logger.error(s"Timed out sending play command to Sonos device ${formattedName.value}")
            )
            .start
            .void

        override def isPlaying: F[Boolean] =
          for {
            grouped <- isGrouped
            controller <- isController
            playing <- if (grouped && !controller)
              groupDevices
                .flatMap(_.flatTraverse(d => d.isController.map(if (_) List(d) else List.empty)))
                .flatMap(_.headOption.fold(F.pure(false))(_.isPlaying))
            else state.get.map(_.isPlaying)
          } yield playing

        override def volume: F[Int] = state.get.map(_.volume)
        override def volumeUp: F[Unit] =
          for {
            vol <- volume
            _ <- if (vol < 100) {
              val newVol = vol + 1
              suspendErrorsEval(underlying.setVolume(newVol)) *> state.update(_.copy(volume = newVol))
            } else { F.unit }
          } yield ()

        override def volumeDown: F[Unit] =
          for {
            vol <- volume
            _ <- if (vol > 0) {
              val newVol = vol - 1
              suspendErrorsEval(underlying.setVolume(newVol)) *> state.update(_.copy(volume = newVol))
            } else { F.unit }
          } yield ()

        override def mute: F[Unit] = suspendErrorsEval(underlying.switchMute())

        override def next: F[Unit] = suspendErrorsEval(underlying.next()) *> refreshNowPlaying *> onUpdate()
        override def previous: F[Unit] = suspendErrorsEval(underlying.previous()) *> refreshNowPlaying *> onUpdate()
        private def refreshController: F[Unit] = suspendErrorsEval(underlying.isCoordinator).flatMap { con =>
          state.update(_.copy(isController = con))
        }
        override def isController: F[Boolean] = state.get.map(_.isController)
        override def playPause: F[Unit] =
          for {
            playing <- isPlaying
            _ <- if (playing) pause else play
          } yield ()

        private def refreshNowPlaying: F[Unit] = _nowPlaying.flatMap { np =>
          state.update(_.copy(nowPlaying = np))
        }

        override def nowPlaying: F[Option[NowPlaying]] = state.get.map(_.nowPlaying)

        override def group: F[Unit] =
          isGrouped.flatMap(
            if (_) F.unit
            else
              masterToJoin.flatMap(_.fold(F.unit) { m =>
                suspendErrorsEval(underlying.join(m.id)) *> state
                  .update(_.copy(isGrouped = true)) *> refreshGroup *> refreshIsPlaying *> refreshController
              }) *> onUpdate()
          )

        override def unGroup: F[Unit] =
          for {
            isC <- isController
            _ <- if (isC) F.unit
            else
              suspendErrorsEval(underlying.unjoin()) *> state
                .update(_.copy(isGrouped = false, isController = true)) *> refreshGroup *> refreshIsPlaying *> onUpdate()
          } yield ()

        override def isGrouped: F[Boolean] = state.get.map(_.isGrouped)

        override def id: String = deviceId

        private def refreshGroup: F[Unit] =
          suspendErrorsEval(underlying.getZoneGroupState.getZonePlayerUIDInGroup.asScala.toSet).flatMap { devs =>
            state.update(_.copy(devicesInGroup = devs))
          }

        override def devicesInGroup: F[Set[String]] = state.get.map(_.devicesInGroup)

        private def masterToJoin: F[Option[SonosDevice[F]]] =
          allDevices.get
            .flatMap(_.toList.flatTraverse {
              case (uid, device) =>
                device.isController.map { isController =>
                  if (isController && uid != id) List(device)
                  else List.empty
                }
            })
            .map(_.headOption)

        private def groupDevices: F[List[SonosDevice[F]]] =
          for {
            group <- devicesInGroup
            devices <- allDevices.get
          } yield devices.filterKeys(group.contains).values.toList

        private def doIfController(f: SonosDevice[F] => F[Unit]): F[Unit] =
          groupDevices
            .flatMap(_.traverse { device =>
              device.isController.flatMap(if (_) f(device) else F.unit)
            })
            .void

        override def refresh: F[Unit] =
          refreshState.flatMap(state.set)

        override def getState: F[DeviceState] = state.get
      }
  }

}
