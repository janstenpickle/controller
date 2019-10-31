package io.janstenpickle.controller.kodi

import cats.{Eq, Parallel}
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.instances.string._
import cats.instances.tuple._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.janstenpickle.controller.kodi.KodiDevice.DeviceState

trait KodiDevice[F[_]] {
  def name: NonEmptyString
  def room: NonEmptyString
  def isPlaying: F[Boolean]
  def setPlaying(playing: Boolean): F[Unit]
  def refresh: F[Unit]
  def getState: F[DeviceState]
  def sendInputAction(action: NonEmptyString): F[Unit]
  def scanVideoLibrary: F[Unit]
  def playerDetails: F[Map[String, String]]
}

object KodiDevice {
  case class DeviceState(isPlaying: Boolean, playDetails: Map[String, String])

  implicit def kodiDeviceEq[F[_]]: Eq[KodiDevice[F]] = Eq.by(d => (d.name.value, d.room.value))

  def apply[F[_]: Parallel](
    client: KodiClient[F],
    deviceName: NonEmptyString,
    deviceRoom: NonEmptyString,
    onUpdate: () => F[Unit]
  )(implicit F: Sync[F]): F[KodiDevice[F]] = {
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

    def setSpeed(speed: Int): F[Unit] = doIfPlaying(
      client
        .send("Player.SetSpeed", Json.fromFields(Map("playerid" -> Json.fromInt(1), "speed" -> Json.fromInt(speed))))
        .void,
      ()
    )

    def refreshState: F[DeviceState] = Parallel.parMap2(isPlaying, playDetails)(DeviceState)

    for {
      initialState <- refreshState
      state <- Ref.of(initialState)
    } yield
      new KodiDevice[F] {
        override def sendInputAction(action: NonEmptyString): F[Unit] =
          client.send("Input.ExecuteAction", Json.fromFields(Map("action" -> Json.fromString(action.value)))).void

        override def isPlaying: F[Boolean] = state.get.map(_.isPlaying)

        override def setPlaying(playing: Boolean): F[Unit] =
          setSpeed(if (playing) 1 else 0) *> state.get.flatMap(st => state.set(st.copy(isPlaying = playing))) *> refresh *> onUpdate()

        override def refresh: F[Unit] = refreshState.flatMap(state.set)

        override def name: NonEmptyString = deviceName

        override def room: NonEmptyString = deviceRoom

        override def getState: F[DeviceState] = state.get

        override def scanVideoLibrary: F[Unit] =
          client.send("VideoLibrary.Scan", Json.fromFields(Map("showdialogs" -> Json.fromBoolean(true)))).void

        override def playerDetails: F[Map[String, String]] = state.get.map(_.playDetails)
      }
  }
}
