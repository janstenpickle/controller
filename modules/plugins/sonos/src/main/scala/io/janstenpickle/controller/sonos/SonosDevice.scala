package io.janstenpickle.controller.sonos

import cats.effect.syntax.spawn._
import cats.effect.syntax.temporal._
import cats.effect.{Async, Ref, Sync}
import cats.instances.list._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Applicative, Parallel}
import com.vmichalak.sonoscontroller
import com.vmichalak.sonoscontroller.model.{PlayState, TrackMetadata}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.discovery.DiscoveredDevice
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue, State, SwitchKey}
import io.janstenpickle.controller.sonos.SonosDevice.DeviceState
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanStatus}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

trait SonosDevice[F[_]] extends SimpleSonosDevice[F] with DiscoveredDevice[F] {
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

  implicit def apply[F[_]: Parallel](
    deviceId: String,
    formattedName: NonEmptyString,
    nonEmptyName: NonEmptyString,
    switchDevice: NonEmptyString,
    deviceKey: DiscoveredDeviceKey,
    deviceValue: DiscoveredDeviceValue,
    underlying: sonoscontroller.SonosDevice,
    allDevices: Ref[F, Map[String, SonosDevice[F]]],
    commandTimeout: FiniteDuration,
    onUpdate: SonosDevice[F] => F[Unit],
    switchEventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  )(implicit F: Async[F], trace: Trace[F]): F[SonosDevice[F]] = {
    def span[A](name: String, extraFields: (String, AttributeValue)*)(k: F[A]): F[A] = trace.span(s"sonos.$name") {
      trace.putAll(
        (Seq[(String, AttributeValue)]("device.id" -> deviceId, "device.name" -> formattedName.value) ++ extraFields): _*
      ) *> k
    }

    def _isPlaying: F[Boolean] = span("read.is.playing") {
      Sync[F]
        .blocking(underlying.getPlayState)
        .map {
          case PlayState.PLAYING => true
          case PlayState.TRANSITIONING => true
          case _ => false
        }
        .handleError {
          case _: IllegalArgumentException => false
        }
    }

    def _isMuted: F[Boolean] = span("read.is.muted") {
      Sync[F].blocking(underlying.isMuted)
    }

    def _nowPlaying: F[Option[NowPlaying]] = span("read.now.playing") {
      Sync[F].blocking(underlying.getCurrentTrackInfo).map { trackInfo =>
        def element(f: TrackMetadata => String): Option[String] = Option(f(trackInfo.getMetadata)).filterNot(_.isEmpty)

        for {
          track <- element(_.getTitle)
          artist <- element(_.getCreator)
        } yield NowPlaying(track, artist, element(_.getAlbum), element(_.getAlbumArtist), element(_.getAlbumArtURI))
      }
    }

    def _isCoordinator: F[Boolean] =
      span("is.coordinator") {
        Sync[F].blocking {
          val devs = underlying.getZoneGroupState.getZonePlayerUIDInGroup
          underlying.isCoordinator || (devs.size() == 1 && devs.contains(deviceId))
        }
      }

    def refreshState: F[DeviceState] = span("refresh.state") {
      Parallel.parMap7(
        span("get.volume")(Sync[F].blocking(underlying.getVolume)),
        span("is.joined")(Sync[F].blocking(underlying.isJoined)),
        _isPlaying,
        _isMuted,
        _nowPlaying,
        _isCoordinator,
        span("devices.in.group")(Sync[F].blocking(underlying.getZoneGroupState.getZonePlayerUIDInGroup.asScala.toSet))
      )(DeviceState.apply)
    }

    for {
      logger <- Slf4jLogger.fromName[F](s"Sonos Device ${formattedName.value}")
      initState <- refreshState
      state <- Ref.of(initState)
    } yield
      new SonosDevice[F] {
        private val playPauseSwitchKey = SwitchKey(switchDevice, formattedName)
        private val groupSwitchKey = SwitchKey(switchDevice, NonEmptyString.unsafeFrom(s"${formattedName}_group"))
        private val muteSwitchKey = SwitchKey(switchDevice, NonEmptyString.unsafeFrom(s"${formattedName}_mute"))

        override def applicative: Applicative[F] = Applicative[F]
        override def name: NonEmptyString = formattedName
        override def label: NonEmptyString = nonEmptyName

        private def updateSwitches(action: F[Unit], switches: List[(SwitchKey, State, String)]) = {
          def publish(exitCase: Option[String]) = switches.parTraverse_ {
            case (key, state, switchType) =>
              switchEventPublisher.publish1(SwitchStateUpdateEvent(key, state, exitCase))
          }

          (action *> publish(None)).handleErrorWith { th =>
            publish(Some(th.getMessage))
          }
        }

        private def refreshIsPlaying: F[Unit] =
          for {
            current <- state.get
            newIsPlaying <- _isPlaying
            _ <- if (current.isPlaying != newIsPlaying)
              updateSwitches(
                state.update(_.copy(isPlaying = newIsPlaying)),
                List((playPauseSwitchKey, State.fromBoolean(newIsPlaying), "play_pause"))
              )
            else F.unit
          } yield ()

        override def play: F[Unit] = span("play") {
          (for {
            grouped <- isGrouped
            controller <- isController
            _ <- trace.putAll("grouped" -> grouped, "controller" -> controller)
            _ <- if (grouped && !controller)
              doIfController(_.play)
            else trace.span("playCmd")(Sync[F].blocking(underlying.play()))
            _ <- state.update(_.copy(isPlaying = true))
            _ <- switchEventPublisher.publish1(SwitchStateUpdateEvent(playPauseSwitchKey, State.On))
            _ <- onUpdate(this)
          } yield ())
            .handleErrorWith(
              th =>
                trace.setStatus(SpanStatus.Internal(th.getMessage)) *> logger
                  .error(th)(s"Failed to send play command on Sonos device ${formattedName.value}")
            )
            .timeoutTo(
              commandTimeout,
              trace.setStatus(SpanStatus.DeadlineExceeded) *> logger.error(
                s"Timed out sending play command to Sonos device ${formattedName.value}"
              )
            )
            .start
            .void
        }

        override def pause: F[Unit] = span("pause") {
          (for {
            grouped <- isGrouped
            controller <- isController
            _ <- trace.putAll("grouped" -> grouped, "controller" -> controller)
            _ <- if (grouped && !controller)
              doIfController(_.pause)
            else trace.span("pauseCmd")(Sync[F].blocking(underlying.pause()))
            _ <- state.update(_.copy(isPlaying = false))
            _ <- switchEventPublisher.publish1(SwitchStateUpdateEvent(playPauseSwitchKey, State.Off))
            _ <- onUpdate(this)
          } yield ())
            .handleErrorWith(
              th =>
                trace.setStatus(SpanStatus.Internal(th.getMessage)) *> logger
                  .error(th)(s"Failed to send pause command on Sonos device ${formattedName.value}")
            )
            .timeoutTo(
              commandTimeout,
              trace.setStatus(SpanStatus.DeadlineExceeded) *> logger.error(
                s"Timed out sending pause command to Sonos device ${formattedName.value}"
              )
            )
            .start
            .void
        }

        override def isPlaying: F[Boolean] = span("is.playing") {
          for {
            grouped <- isGrouped
            controller <- isController
            _ <- trace.putAll("grouped" -> grouped, "controller" -> controller)
            playing <- if (grouped && !controller)
              groupDevices
                .flatMap(_.parFlatTraverse(d => d.isController.map(if (_) List(d) else List.empty)))
                .flatMap(_.headOption.fold(F.pure(false))(_.isPlaying))
            else state.get.map(_.isPlaying)
          } yield playing
        }

        override def volume: F[Int] = span("volume") { state.get.map(_.volume) }
        override def volumeUp: F[Unit] = span("volume.up") {
          for {
            vol <- volume
            _ <- trace.put("current.volume", vol)
            _ <- if (vol < 100) {
              val newVol = vol + 1
              Sync[F].blocking(underlying.setVolume(newVol)) *> state.update(_.copy(volume = newVol)) *> trace
                .put("new.volume", newVol)
            } else {
              F.unit
            }
          } yield ()
        }

        override def volumeDown: F[Unit] = span("volume.down") {
          for {
            vol <- volume
            _ <- trace.put("current.volume", vol)
            _ <- if (vol > 0) {
              val newVol = vol - 1
              Sync[F].blocking(underlying.setVolume(newVol)) *> state.update(_.copy(volume = newVol)) *> trace
                .put("new.volume", newVol)
            } else {
              F.unit
            }
          } yield ()
        }

        override def mute: F[Unit] = span("mute") {
          Sync[F].blocking(underlying.setMute(true)) *> state.update(_.copy(isMuted = true)) *> onUpdate(this)
        }

        override def unMute: F[Unit] = span("unmute") {
          Sync[F].blocking(underlying.setMute(false)) *> state.update(_.copy(isMuted = false)) *> onUpdate(this)
        }

        override def isMuted: F[Boolean] = span("is.muted") { state.get.map(_.isMuted) }

        override def next: F[Unit] = span("next") {
          Sync[F].blocking(underlying.next()) *> refreshNowPlaying *> onUpdate(this)
        }
        override def previous: F[Unit] = span("previous") {
          Sync[F].blocking(underlying.previous()) *> refreshNowPlaying *> onUpdate(this)
        }
        private def refreshController: F[Unit] = span("refresh.controller") {
          _isCoordinator.flatMap { con =>
            state.update(_.copy(isController = con))
          }
        }
        override def isController: F[Boolean] = span("is.controller") { state.get.map(_.isController) }
        override def playPause: F[Unit] = span("play.pause") {
          for {
            playing <- isPlaying
            _ <- trace.put("is.playing", playing)
            _ <- if (playing) pause else play
          } yield ()
        }

        private def refreshNowPlaying: F[Unit] = span("refresh.now.playing") {
          _nowPlaying.flatMap { np =>
            state.update(_.copy(nowPlaying = np))
          }
        }

        override def nowPlaying: F[Option[NowPlaying]] = span("now.playing") { state.get.map(_.nowPlaying) }

        override def group: F[Unit] = span("Group") {
          isGrouped.flatMap { grouped =>
            trace.put("current.grouped", grouped) *> {
              if (grouped) trace.put("new.grouped", grouped)
              else
                masterToJoin.flatMap(_.fold(F.unit) { m =>
                  Sync[F].blocking(underlying.join(m.id)) *> state
                    .update(_.copy(isGrouped = true)) *> refreshGroup *> refreshIsPlaying *> refreshController *> trace
                    .putAll("master.id" -> m.id, "master.name" -> m.name.value)
                }) *> onUpdate(this)
            }
          }
        }

        override def unGroup: F[Unit] = span("ungroup") {
          for {
            isC <- isController
            _ <- if (isC) F.unit
            else
              Sync[F].blocking(underlying.unjoin()) *> state
                .update(_.copy(isGrouped = false, isController = true)) *> refreshGroup *> refreshIsPlaying *> onUpdate(
                this
              ) *> trace
                .putAll("current.grouped" -> true, "new.grouped" -> false)
          } yield ()
        }

        override def isGrouped: F[Boolean] = span("is.grouped") { state.get.map(_.isGrouped) }

        override def id: String = deviceId

        private def refreshGroup: F[Unit] = span("refresh.group") {
          Sync[F].blocking(underlying.getZoneGroupState.getZonePlayerUIDInGroup.asScala.toSet).flatMap { devs =>
            trace.put("group.devices", devs.mkString(",")) *> state.update(_.copy(devicesInGroup = devs))
          }
        }

        override def devicesInGroup: F[Set[String]] = span("gevices.in.group") { state.get.map(_.devicesInGroup) }

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

        override def refresh: F[Unit] = span("refresh") {
          for {
            currentState <- state.get
            newState <- refreshState

            ops = {
              if (newState.isPlaying != currentState.isPlaying)
                List((playPauseSwitchKey, State.fromBoolean(newState.isPlaying), "play_pause"))
              else List.empty
            } ++ {
              if (newState.isGrouped != currentState.isGrouped)
                List((groupSwitchKey, State.fromBoolean(newState.isGrouped), "group"))
              else List.empty
            } ++ {
              if (newState.isMuted != currentState.isMuted)
                List((muteSwitchKey, State.fromBoolean(newState.isMuted), "mute"))
              else List.empty
            }

            _ <- updateSwitches(state.set(newState), ops)
          } yield ()
        }

        override def getState: F[DeviceState] = span("get.state") { state.get }

        override def key: DiscoveredDeviceKey = deviceKey
        override def value: DiscoveredDeviceValue = deviceValue

        override def updatedKey: F[String] = getState.map { state =>
          s"${name}${label}${state.isPlaying}${state.nowPlaying}${state.isMuted}${state.isGrouped}"
        }
      }
  }

}
