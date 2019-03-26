package io.janstenpickle.controller.sonos

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, Sync, Timer}
import cats.effect.syntax.concurrent._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.instances.list._
import cats.syntax.apply._
import com.vmichalak.sonoscontroller
import com.vmichalak.sonoscontroller.model.{PlayState, TrackMetadata}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect.suspendErrorsEvalOn

import scala.concurrent.ExecutionContext
import scala.collection.JavaConverters._
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
}

object SonosDevice {

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

    for {
      volume <- suspendErrorsEval(underlying.getVolume)
      currentVol <- Ref.of(volume)
      isGrouped <- suspendErrorsEval(underlying.isJoined)
      grouped <- Ref.of(isGrouped)
      isPlaying <- _isPlaying
      playing <- Ref.of(isPlaying)
      nowPlaying <- _nowPlaying
      playingInfo <- Ref.of(nowPlaying)
      isController <- suspendErrorsEval(underlying.isCoordinator)
      controller <- Ref.of(isController)
      devicesInGroup <- suspendErrorsEval(underlying.getZoneGroupState.getZonePlayerUIDInGroup.asScala.toSet)
      devices <- Ref.of(devicesInGroup)
    } yield
      new SonosDevice[F] {
        override def applicative: Applicative[F] = Applicative[F]
        override def name: NonEmptyString = formattedName
        override def label: NonEmptyString = nonEmptyName

        private def refreshIsPlaying: F[Unit] = _isPlaying.flatMap(playing.set)
        override def play: F[Unit] =
          (for {
            grouped <- isGrouped
            controller <- isController
            _ <- if (grouped && !controller)
              doIfController(_.play *> playing.set(true))
            else suspendErrorsEval(underlying.play()) *> playing.set(true)
            _ <- onUpdate()
          } yield ()).timeoutTo(commandTimeout, F.unit).start.void

        override def pause: F[Unit] =
          (for {
            grouped <- isGrouped
            controller <- isController
            _ <- if (grouped && !controller)
              doIfController(_.pause *> playing.set(false))
            else suspendErrorsEval(underlying.pause()) *> playing.set(false)
            _ <- onUpdate()
          } yield ()).timeoutTo(commandTimeout, F.unit).start.void

        override def isPlaying: F[Boolean] =
          for {
            grouped <- isGrouped
            controller <- isController
            playing <- if (grouped && !controller)
              groupDevices
                .flatMap(_.flatTraverse(d => d.isController.map(if (_) List(d) else List.empty)))
                .flatMap(_.headOption.fold(F.pure(false))(_.isPlaying))
            else playing.get
          } yield playing

        private def refreshVolume: F[Unit] = suspendErrorsEval(underlying.getVolume).flatMap(currentVol.set)
        override def volume: F[Int] = currentVol.get
        override def volumeUp: F[Unit] =
          for {
            vol <- volume
            _ <- if (vol < 100) {
              val newVol = vol + 1
              suspendErrorsEval(underlying.setVolume(newVol)) *> currentVol.set(newVol)
            } else { F.unit }
          } yield ()

        override def volumeDown: F[Unit] =
          for {
            vol <- volume
            _ <- if (vol > 0) {
              val newVol = vol - 1
              suspendErrorsEval(underlying.setVolume(newVol)) *> currentVol.set(newVol)
            } else { F.unit }
          } yield ()
        override def mute: F[Unit] = suspendErrorsEval(underlying.switchMute())
        override def next: F[Unit] = suspendErrorsEval(underlying.next()) *> refreshNowPlaying *> onUpdate()
        override def previous: F[Unit] = suspendErrorsEval(underlying.previous()) *> refreshNowPlaying *> onUpdate()
        private def refreshController: F[Unit] = suspendErrorsEval(underlying.isCoordinator).flatMap(controller.set)
        override def isController: F[Boolean] = controller.get
        override def playPause: F[Unit] =
          for {
            playing <- isPlaying
            _ <- if (playing) pause else play
          } yield ()

        private def refreshNowPlaying: F[Unit] = _nowPlaying.flatMap(playingInfo.set)
        override def nowPlaying: F[Option[NowPlaying]] = playingInfo.get

        override def group: F[Unit] =
          isGrouped.flatMap(
            if (_) F.unit
            else
              masterToJoin.flatMap(_.fold(F.unit) { m =>
                suspendErrorsEval(underlying.join(m.id)) *> grouped
                  .set(true) *> refreshGroup *> refreshIsPlaying *> refreshController
              }) *> onUpdate()
          )

        override def unGroup: F[Unit] =
          for {
            isC <- isController
            _ <- if (isC) F.unit
            else
              suspendErrorsEval(underlying.unjoin()) *> grouped.set(false) *> refreshGroup *> refreshIsPlaying *> controller
                .set(true) *> onUpdate()
          } yield ()

        private def refreshIsGrouped: F[Unit] = suspendErrorsEval(underlying.isJoined).flatMap(grouped.set)
        override def isGrouped: F[Boolean] = grouped.get

        override def id: String = deviceId

        private def refreshGroup: F[Unit] =
          suspendErrorsEval(underlying.getZoneGroupState.getZonePlayerUIDInGroup.asScala.toSet).flatMap(devices.set)

        override def devicesInGroup: F[Set[String]] = devices.get

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
          refreshIsPlaying *> refreshNowPlaying *> refreshIsGrouped *> refreshVolume *> refreshGroup *> refreshController
      }
  }

}
