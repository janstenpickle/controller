package io.janstenpickle.controller.sonos

import cats.Applicative
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
  def label: NonEmptyString
  def isPlaying: F[Boolean]
  def nowPlaying: F[Option[NowPlaying]]
  def volume: F[Int]
  def group: F[Unit]
  def unGroup: F[Unit]
  def isGrouped: F[Boolean]
}

object SonosDevice { outer =>
  private def isPlaying[F[_]: Sync: ContextShift](
    device: sonoscontroller.SonosDevice,
    ec: ExecutionContext
  ): F[Boolean] =
    suspendErrorsEvalOn(device.getPlayState, ec).map {
      case PlayState.PLAYING => true
      case PlayState.TRANSITIONING => true
      case _ => false
    }

  private def nowPlaying[F[_]: Sync: ContextShift](
    device: sonoscontroller.SonosDevice,
    ec: ExecutionContext
  ): F[Option[NowPlaying]] =
    suspendErrorsEvalOn(device.getCurrentTrackInfo, ec).map { trackInfo =>
      def element(f: TrackMetadata => String): Option[String] = Option(f(trackInfo.getMetadata)).filterNot(_.isEmpty)

      for {
        track <- element(_.getTitle)
        artist <- element(_.getCreator)
      } yield NowPlaying(track, artist, element(_.getAlbum), element(_.getAlbumArtist), element(_.getAlbumArtURI))
    }

  implicit def apply[F[_]: ContextShift: Timer](
    formattedName: NonEmptyString,
    nonEmptyName: NonEmptyString,
    underlying: sonoscontroller.SonosDevice,
    others: List[sonoscontroller.SonosDevice],
    commandTimeout: FiniteDuration,
    ec: ExecutionContext,
    onUpdate: () => F[Unit]
  )(implicit F: Concurrent[F]): SonosDevice[F] = {
    def suspendErrorsEval[A](thunk: => A): F[A] = suspendErrorsEvalOn(thunk, ec)

    def masterUid: F[Option[String]] =
      for {
        thisUid <- suspendErrorsEval(underlying.getSpeakerInfo.getLocalUID)
        uids <- others.flatTraverse { device =>
          for {
            uid <- suspendErrorsEval(device.getSpeakerInfo.getLocalUID)
            isController <- suspendErrorsEval(device.isCoordinator)
          } yield if (isController && uid != thisUid) List(uid) else List.empty
        }
      } yield uids.headOption

    new SonosDevice[F] {
      override def applicative: Applicative[F] = Applicative[F]
      override def name: NonEmptyString = formattedName
      override def label: NonEmptyString = nonEmptyName
      override def play: F[Unit] =
        (for {
          grouped <- isGrouped
          _ <- if (grouped) suspendErrorsEval(underlying.getZoneGroupState.getSonosDevicesInGroup.asScala.collect {
            case device if device.isCoordinator => device.play()
          }).void
          else suspendErrorsEval(underlying.play())
          _ <- onUpdate()
        } yield ()).timeout(commandTimeout).start.void

      override def pause: F[Unit] =
        (for {
          grouped <- isGrouped
          _ <- if (grouped) suspendErrorsEval(underlying.getZoneGroupState.getSonosDevicesInGroup.asScala.collect {
            case device if device.isCoordinator => device.pause()
          }).void
          else suspendErrorsEval(underlying.pause())
          _ <- onUpdate()
        } yield ()).timeout(commandTimeout).start.void

      override def isPlaying: F[Boolean] =
        for {
          grouped <- isGrouped
          playing <- if (grouped)
            suspendErrorsEval(underlying.getZoneGroupState.getSonosDevicesInGroup.asScala.find(_.isCoordinator))
              .flatMap(_.fold(F.pure(false))(outer.isPlaying(_, ec)))
          else outer.isPlaying(underlying, ec)
        } yield playing

      override def volume: F[Int] = suspendErrorsEval(underlying.getVolume)
      override def volumeUp: F[Unit] =
        for {
          vol <- volume
          _ <- if (vol < 100) suspendErrorsEval(underlying.setVolume(vol + 1)) else F.unit
        } yield ()
      override def volumeDown: F[Unit] =
        for {
          vol <- volume
          _ <- if (vol > 0) suspendErrorsEval(underlying.setVolume(vol - 1)) else F.unit
        } yield ()
      override def mute: F[Unit] = suspendErrorsEval(underlying.switchMute())
      override def next: F[Unit] = suspendErrorsEval(underlying.next()) *> onUpdate()
      override def previous: F[Unit] = suspendErrorsEval(underlying.previous()) *> onUpdate()
      override def isController: F[Boolean] = suspendErrorsEval(underlying.isCoordinator)
      override def playPause: F[Unit] =
        (for {
          playing <- isPlaying
          _ <- if (playing) pause else play
        } yield ()).timeout(commandTimeout).start.void

      override def nowPlaying: F[Option[NowPlaying]] = outer.nowPlaying(underlying, ec)

      override def group: F[Unit] =
        isGrouped.flatMap(
          if (_) F.unit
          else
            masterUid.flatMap(_.fold(F.unit) { masterUid =>
              suspendErrorsEval(underlying.join(masterUid))
            }) *> onUpdate()
        )

      override def unGroup: F[Unit] =
        for {
          isC <- isController
          _ <- if (isC) F.unit else suspendErrorsEval(underlying.unjoin()) *> onUpdate()
        } yield ()

      override def isGrouped: F[Boolean] = suspendErrorsEval(underlying.isJoined)
    }
  }
}
