package io.janstenpickle.controller.sonos

import cats.{Applicative, Parallel}
import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Concurrent, ContextShift, Timer}
import cats.instances.list._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import com.vmichalak.sonoscontroller
import com.vmichalak.sonoscontroller.model.{PlayState, TrackMetadata}
import eu.timepit.refined.types.string.NonEmptyString
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.sonos.SonosDevice.DeviceState
import natchez.{Trace, TraceValue}

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
  def isMuted: F[Boolean]
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
    isMuted: Boolean,
    nowPlaying: Option[NowPlaying],
    isController: Boolean,
    devicesInGroup: Set[String]
  )

  implicit def apply[F[_]: ContextShift: Timer: Parallel](
    deviceId: String,
    formattedName: NonEmptyString,
    nonEmptyName: NonEmptyString,
    underlying: sonoscontroller.SonosDevice,
    allDevices: Ref[F, Map[String, SonosDevice[F]]],
    commandTimeout: FiniteDuration,
    blocker: Blocker,
    onUpdate: () => F[Unit]
  )(implicit F: Concurrent[F], trace: Trace[F]): F[SonosDevice[F]] = {
    def span[A](name: String, extraFields: (String, TraceValue)*)(k: F[A]): F[A] = trace.span(s"sonos$name") {
      trace.put(
        Seq[(String, TraceValue)]("device.id" -> deviceId, "device.name" -> formattedName.value) ++ extraFields: _*
      ) *> k
    }

    def _isPlaying: F[Boolean] = span("ReadIsPlaying") {
      blocker.delay(underlying.getPlayState).map {
        case PlayState.PLAYING => true
        case PlayState.TRANSITIONING => true
        case _ => false
      }
    }

    def _isMuted: F[Boolean] = span("ReadIsMuted") {
      blocker.delay(underlying.isMuted)
    }

    def _nowPlaying: F[Option[NowPlaying]] = span("ReadNowPlaying") {
      blocker.delay(underlying.getCurrentTrackInfo).map { trackInfo =>
        def element(f: TrackMetadata => String): Option[String] = Option(f(trackInfo.getMetadata)).filterNot(_.isEmpty)

        for {
          track <- element(_.getTitle)
          artist <- element(_.getCreator)
        } yield NowPlaying(track, artist, element(_.getAlbum), element(_.getAlbumArtist), element(_.getAlbumArtURI))
      }
    }

    def refreshState: F[DeviceState] = span("RefreshState") {
      Parallel.parMap7(
        trace.span("getVolume")(blocker.delay(underlying.getVolume)),
        trace.span("isJoined")(blocker.delay(underlying.isJoined)),
        _isPlaying,
        _isMuted,
        _nowPlaying,
        trace.span("isCoordinator")(blocker.delay(underlying.isCoordinator)),
        trace.span("devicesInGroup")(blocker.delay(underlying.getZoneGroupState.getZonePlayerUIDInGroup.asScala.toSet))
      )(DeviceState.apply)
    }

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

        override def play: F[Unit] = span("Play") {
          (for {
            grouped <- isGrouped
            controller <- isController
            _ <- trace.put("grouped" -> grouped, "controller" -> controller)
            _ <- if (grouped && !controller)
              doIfController(_.play)
            else trace.span("playCmd")(blocker.delay(underlying.play()))
            _ <- state.update(_.copy(isPlaying = true))
            _ <- onUpdate()
          } yield ())
            .handleErrorWith(
              th =>
                trace.put("error" -> true, "reason" -> th.getMessage) *> logger
                  .error(th)(s"Failed to send play command on Sonos device ${formattedName.value}")
            )
            .timeoutTo(
              commandTimeout,
              trace.put("error" -> true, "reason" -> "command timed out") *> logger.error(
                s"Timed out sending play command to Sonos device ${formattedName.value}"
              )
            )
            .start
            .void
        }

        override def pause: F[Unit] = span("Pause") {
          (for {
            grouped <- isGrouped
            controller <- isController
            _ <- trace.put("grouped" -> grouped, "controller" -> controller)
            _ <- if (grouped && !controller)
              doIfController(_.pause)
            else trace.span("pauseCmd")(blocker.delay(underlying.pause()))
            _ <- state.update(_.copy(isPlaying = false))
            _ <- onUpdate()
          } yield ())
            .handleErrorWith(
              th =>
                trace.put("error" -> true, "reason" -> th.getMessage) *> logger
                  .error(th)(s"Failed to send pause command on Sonos device ${formattedName.value}")
            )
            .timeoutTo(
              commandTimeout,
              trace.put("error" -> true, "reason" -> "command timed out") *> logger.error(
                s"Timed out sending pause command to Sonos device ${formattedName.value}"
              )
            )
            .start
            .void
        }

        override def isPlaying: F[Boolean] = span("IsPlaying") {
          for {
            grouped <- isGrouped
            controller <- isController
            _ <- trace.put("grouped" -> grouped, "controller" -> controller)
            playing <- if (grouped && !controller)
              groupDevices
                .flatMap(_.parFlatTraverse(d => d.isController.map(if (_) List(d) else List.empty)))
                .flatMap(_.headOption.fold(F.pure(false))(_.isPlaying))
            else state.get.map(_.isPlaying)
          } yield playing
        }

        override def volume: F[Int] = span("Volume") { state.get.map(_.volume) }
        override def volumeUp: F[Unit] = span("VolumeUp") {
          for {
            vol <- volume
            _ <- trace.put("current.volume" -> vol)
            _ <- if (vol < 100) {
              val newVol = vol + 1
              blocker.delay(underlying.setVolume(newVol)) *> state.update(_.copy(volume = newVol)) *> trace.put(
                "new.volume" -> newVol
              )
            } else {
              F.unit
            }
          } yield ()
        }

        override def volumeDown: F[Unit] = span("VolumeDown") {
          for {
            vol <- volume
            _ <- trace.put("current.volume" -> vol)
            _ <- if (vol > 0) {
              val newVol = vol - 1
              blocker.delay(underlying.setVolume(newVol)) *> state.update(_.copy(volume = newVol)) *> trace.put(
                "new.volume" -> newVol
              )
            } else {
              F.unit
            }
          } yield ()
        }

        override def mute: F[Unit] = span("Mute") {
          blocker.delay(underlying.setMute(true)) *> state.update(_.copy(isMuted = true)) *> onUpdate()
        }

        override def unMute: F[Unit] = span("UnMute") {
          blocker.delay(underlying.setMute(false)) *> state.update(_.copy(isMuted = false)) *> onUpdate()
        }

        override def isMuted: F[Boolean] = span("IsMuted") { state.get.map(_.isMuted) }

        override def next: F[Unit] = span("Next") {
          blocker.delay(underlying.next()) *> refreshNowPlaying *> onUpdate()
        }
        override def previous: F[Unit] = span("Previous") {
          blocker.delay(underlying.previous()) *> refreshNowPlaying *> onUpdate()
        }
        private def refreshController: F[Unit] = span("RefreshController") {
          blocker.delay(underlying.isCoordinator).flatMap { con =>
            state.update(_.copy(isController = con))
          }
        }
        override def isController: F[Boolean] = span("IsController") { state.get.map(_.isController) }
        override def playPause: F[Unit] = span("PlayPause") {
          for {
            playing <- isPlaying
            _ <- trace.put("is.playing" -> playing)
            _ <- if (playing) pause else play
          } yield ()
        }

        private def refreshNowPlaying: F[Unit] = span("RefreshNowPlaying") {
          _nowPlaying.flatMap { np =>
            state.update(_.copy(nowPlaying = np))
          }
        }

        override def nowPlaying: F[Option[NowPlaying]] = span("NowPlaying") { state.get.map(_.nowPlaying) }

        override def group: F[Unit] = span("Group") {
          isGrouped.flatMap { grouped =>
            trace.put("current.grouped" -> grouped) *> {
              if (grouped) trace.put("new.grouped" -> grouped)
              else
                masterToJoin.flatMap(_.fold(F.unit) { m =>
                  blocker.delay(underlying.join(m.id)) *> state
                    .update(_.copy(isGrouped = true)) *> refreshGroup *> refreshIsPlaying *> refreshController *> trace
                    .put("master.id" -> m.id, "master.name" -> m.name.value)
                }) *> onUpdate()
            }
          }
        }

        override def unGroup: F[Unit] = span("UnGroup") {
          for {
            isC <- isController
            _ <- if (isC) F.unit
            else
              blocker.delay(underlying.unjoin()) *> state
                .update(_.copy(isGrouped = false, isController = true)) *> refreshGroup *> refreshIsPlaying *> onUpdate() *> trace
                .put("current.grouped" -> true, "new.grouped" -> false)
          } yield ()
        }

        override def isGrouped: F[Boolean] = span("IsGrouped") { state.get.map(_.isGrouped) }

        override def id: String = deviceId

        private def refreshGroup: F[Unit] = span("RefreshGroup") {
          blocker.delay(underlying.getZoneGroupState.getZonePlayerUIDInGroup.asScala.toSet).flatMap { devs =>
            trace.put("group.devices" -> devs.mkString(",")) *> state.update(_.copy(devicesInGroup = devs))
          }
        }

        override def devicesInGroup: F[Set[String]] = span("DevicesInGroup") { state.get.map(_.devicesInGroup) }

        private def masterToJoin: F[Option[SonosDevice[F]]] =
          allDevices.get
            .flatMap(_.toList.parFlatTraverse {
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
            .flatMap(_.parTraverse { device =>
              device.isController.flatMap(if (_) f(device) else F.unit)
            })
            .void

        override def refresh: F[Unit] = span("Refresh") {
          refreshState.flatMap(state.set)
        }

        override def getState: F[DeviceState] = span("GerState") { state.get }
      }
  }

}
