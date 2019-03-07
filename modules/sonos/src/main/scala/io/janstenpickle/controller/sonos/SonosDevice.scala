package io.janstenpickle.controller.sonos

import cats.Applicative
import cats.effect.{ContextShift, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.vmichalak.sonoscontroller
import com.vmichalak.sonoscontroller.model.PlayState
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect.suspendErrorsEvalOn

import scala.concurrent.ExecutionContext

trait SonosDevice[F[_]] extends SimpleSonosDevice[F] {
  protected[sonos] def comparableString: String
  def label: NonEmptyString
  def isPlaying: F[Boolean]
  def nowPlaying: F[Option[NowPlaying]]
  def volume: F[Int]
}

object SonosDevice { outer =>
  private[sonos] def isPlaying[F[_]: Sync: ContextShift](
    device: sonoscontroller.SonosDevice,
    ec: ExecutionContext
  ): F[Boolean] =
    suspendErrorsEvalOn(device.getPlayState, ec).map {
      case PlayState.PLAYING => true
      case PlayState.TRANSITIONING => true
      case _ => false
    }

  private[sonos] def nowPlaying[F[_]: Sync: ContextShift](
    device: sonoscontroller.SonosDevice,
    ec: ExecutionContext
  ): F[Option[NowPlaying]] =
    suspendErrorsEvalOn(device.getCurrentTrackInfo, ec).map { trackInfo =>
      for {
        track <- Option(trackInfo.getMetadata.getTitle).filterNot(_.isEmpty)
        artist <- Option(trackInfo.getMetadata.getCreator).filterNot(_.isEmpty)
      } yield NowPlaying(track, artist)
    }

  implicit def apply[F[_]: ContextShift](
    formattedName: NonEmptyString,
    nonEmptyName: NonEmptyString,
    _isPlaying: Boolean,
    _nowPlaying: Option[NowPlaying],
    underlying: sonoscontroller.SonosDevice,
    ec: ExecutionContext
  )(implicit F: Sync[F]): SonosDevice[F] = {
    def suspendErrorsEval[A](thunk: => A): F[A] = suspendErrorsEvalOn(thunk, ec)

    new SonosDevice[F] {
      override def applicative: Applicative[F] = Applicative[F]
      override protected[sonos] def comparableString: String = s"$formattedName$nonEmptyName${_isPlaying}${_nowPlaying}"
      override def name: NonEmptyString = formattedName
      override def label: NonEmptyString = nonEmptyName
      override def play: F[Unit] = suspendErrorsEval(underlying.play())
      override def pause: F[Unit] = suspendErrorsEval(underlying.pause())
      override def isPlaying: F[Boolean] = outer.isPlaying(underlying, ec)
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
      override def next: F[Unit] = suspendErrorsEval(underlying.next())
      override def previous: F[Unit] = suspendErrorsEval(underlying.previous())
      override def isController: F[Boolean] = suspendErrorsEval(underlying.isCoordinator)
      override def playPause: F[Unit] =
        for {
          playing <- isPlaying
          _ <- if (playing) pause else play
        } yield ()

      override def nowPlaying: F[Option[NowPlaying]] = outer.nowPlaying(underlying, ec)
    }
  }
}
