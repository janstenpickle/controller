package io.janstenpickle.controller.kodi

import cats.{Eq, Parallel}
import cats.effect.{Clock, ExitCase, Sync}
import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.instances.string._
import cats.instances.tuple._
import cats.syntax.apply._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.janstenpickle.controller.discovery.DiscoveredDevice
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.kodi.KodiDevice.DeviceState
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue, State, SwitchKey, SwitchMetadata}
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.trace4cats.inject.Trace

trait KodiDevice[F[_]] extends DiscoveredDevice[F] {
  def name: NonEmptyString
  def room: NonEmptyString
  def host: String
  def isPlaying: F[Boolean]
  def setPlaying(playing: Boolean): F[Unit]
  def isMuted: F[Boolean]
  def setMuted(muted: Boolean): F[Unit]
  def getState: F[DeviceState]
  def sendInputAction(action: NonEmptyString): F[Unit]
  def scanVideoLibrary: F[Unit]
  def playerDetails: F[Map[String, String]]
  def getVolume: F[Int]
  def volumeUp: F[Unit]
  def volumeDown: F[Unit]
}

object KodiDevice {
  case class DeviceState(isPlaying: Boolean, isMuted: Boolean, playDetails: Map[String, String], volume: Int)

  implicit def kodiDeviceEq[F[_]]: Eq[KodiDevice[F]] = Eq.by(d => (d.name.value, d.room.value))

  def apply[F[_]: Parallel: Clock](
    client: KodiClient[F],
    deviceName: NonEmptyString,
    deviceRoom: NonEmptyString,
    deviceHost: String,
    switchDevice: NonEmptyString,
    deviceKey: DiscoveredDeviceKey,
    switchEventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  )(implicit F: Sync[F], trace: Trace[F]): F[KodiDevice[F]] = {
    def playInfo: F[Option[Json]] =
      client
        .send("Player.GetItem", Json.fromFields(Map("playerid" -> Json.fromInt(1))))
        .map(_.asObject.flatMap(_.toMap.get("item")))

    def playDetails: F[Map[String, String]] =
      playInfo.map(
        _.flatMap(_.asObject.map(_.toMap.flatMap { case (k, v) => v.as[String].toOption.map(k -> _) }))
          .getOrElse(Map.empty)
      )

    def doIfPlaying[A](op: F[A], default: A): F[A] =
      playInfo.map(_.exists(_.asObject.exists(_.contains("id")))).flatMap(if (_) op else F.pure(default))

    def isPlaying: F[Boolean] =
      doIfPlaying(
        client
          .send(
            "Player.GetProperties",
            Json.fromFields(
              Map("playerid" -> Json.fromInt(1), "properties" -> Json.fromValues(List(Json.fromString("speed"))))
            )
          )
          .map(
            _.asObject
              .flatMap(_.toMap.get("speed").flatMap(_.as[Int].toOption))
              .exists(_ != 0)
          ),
        false
      )

    def isMuted: F[Boolean] =
      client
        .send(
          "Application.GetProperties",
          Json.fromFields(Map("properties" -> Json.fromValues(List(Json.fromString("muted")))))
        )
        .map(_.asObject.flatMap(_.toMap.get("muted").flatMap(_.asBoolean)).getOrElse(false))

    def getVolume: F[Int] =
      client
        .send(
          "Application.GetProperties",
          Json.fromFields(Map("properties" -> Json.fromValues(List(Json.fromString("volume")))))
        )
        .map(_.asObject.flatMap(_.toMap.get("volume").flatMap(_.as[Int].toOption)).getOrElse(0))

    def setSpeed(speed: Int): F[Unit] = doIfPlaying(
      client
        .send("Player.SetSpeed", Json.fromFields(Map("playerid" -> Json.fromInt(1), "speed" -> Json.fromInt(speed))))
        .void,
      ()
    )

    def refreshState: F[DeviceState] = Parallel.parMap4(isPlaying, isMuted, playDetails, getVolume)(DeviceState)

    for {
      initialState <- refreshState
      state <- Ref.of(initialState)
    } yield
      new KodiDevice[F] {
        private val mutedSwitchKey = SwitchKey(switchDevice, NonEmptyString.unsafeFrom(s"${deviceName.value}_mute"))
        private val playPauseSwitchKey =
          SwitchKey(switchDevice, NonEmptyString.unsafeFrom(s"${deviceName.value}_playpause"))

        private def updateSwitches(action: F[Unit], switches: List[(SwitchKey, State, String)]) = {
          def publish(exitCase: Option[String]) = switches.parTraverse_ {
            case (switchKey, state, switchType) =>
              switchEventPublisher.publish1(SwitchStateUpdateEvent(switchKey, state, exitCase))
          }

          (action *> publish(None)).handleErrorWith { th =>
            publish(Some(th.getMessage))
          }
        }

        override def sendInputAction(action: NonEmptyString): F[Unit] =
          client.send("Input.ExecuteAction", Json.fromFields(Map("action" -> Json.fromString(action.value)))).void

        override def isPlaying: F[Boolean] = state.get.map(_.isPlaying)

        override def setPlaying(playing: Boolean): F[Unit] =
          setSpeed(if (playing) 1 else 0) *> state.get.flatMap(st => state.set(st.copy(isPlaying = playing)))

        override def refresh: F[Unit] =
          for {
            current <- state.get
            newState <- refreshState
            ops = {
              if (newState.isPlaying != current.isPlaying)
                List((playPauseSwitchKey, State.fromBoolean(current.isPlaying), "playpause"))
              else List.empty
            } ++ {
              if (newState.isMuted != current.isMuted)
                List((mutedSwitchKey, State.fromBoolean(current.isMuted), "mute"))
              else List.empty
            }
            _ <- updateSwitches(state.set(newState), ops)
          } yield ()

        override def name: NonEmptyString = deviceName

        override def room: NonEmptyString = deviceRoom

        override def getState: F[DeviceState] = state.get

        override def scanVideoLibrary: F[Unit] =
          client.send("VideoLibrary.Scan", Json.fromFields(Map("showdialogs" -> Json.fromBoolean(true)))).void

        override def playerDetails: F[Map[String, String]] = state.get.map(_.playDetails)

        override def isMuted: F[Boolean] = state.get.map(_.isMuted)

        override def setMuted(muted: Boolean): F[Unit] = state.get.flatMap { st =>
          if (st.isMuted != muted)
            sendInputAction(Commands.Mute) *> state.get
              .flatMap(st => state.set(st.copy(isMuted = muted)))
          else F.unit
        }

        override def getVolume: F[Int] = state.get.map(_.volume)

        override def volumeUp: F[Unit] = state.get.flatMap { st =>
          if (st.volume < 100)
            sendInputAction(Commands.VolUp) *> state.set(st.copy(volume = st.volume + 1))
          else F.unit
        }

        override def volumeDown: F[Unit] = state.get.flatMap { st =>
          if (st.volume > 0)
            sendInputAction(Commands.VolDown) *> state.set(st.copy(volume = st.volume - 1))
          else F.unit
        }

        override def key: DiscoveredDeviceKey = deviceKey

        override def value: DiscoveredDeviceValue = DiscoveredDeviceValue(name, Some(room))

        override def updatedKey: F[String] = getState.map { state =>
          s"${name}${room}${state.isPlaying}${state.isMuted}"
        }

        override def host: String = deviceHost
      }
  }
}
