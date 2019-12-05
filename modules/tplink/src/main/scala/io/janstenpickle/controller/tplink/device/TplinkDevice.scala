package io.janstenpickle.controller.tplink.device

import cats.effect._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import eu.timepit.refined.refineMV
import cats.{Eq, Monad}
import cats.effect.concurrent.Ref
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Decoder, Json}
import io.janstenpickle.control.switch.polling.{PollingSwitch, PollingSwitchErrors}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.model.{Room, State}
import io.janstenpickle.controller.switch.Switch
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.tplink.Constants._
import io.janstenpickle.controller.tplink.TplinkClient
import natchez.Trace
import cats.syntax.apply._
import cats.syntax.functor._
import cats.instances.string._

import scala.concurrent.duration._

sealed trait TplinkDevice[F[_]] extends Switch[F] {
  def refresh: F[Unit]
  def rename(name: NonEmptyString, room: Option[Room]): F[Unit]
}

object TplinkDevice {

  trait SmartPlug[F[_]] extends TplinkDevice[F]

  trait SmartBulb[F[_]] extends TplinkDevice[F] {
//    def room: Room
  }

  private final val SetRelayState = "set_relay_state"
  private final val SetDevAlias = "set_dev_alias"

  private final val SwitchOnCommand = s"""{"$System":{"$SetRelayState":{"state":1}}}}"""
  private final val SwitchOffCommand = s"""{"$System":{"$SetRelayState":{"state":0}}}}"""

  case class Config(
    name: NonEmptyString,
    host: NonEmptyString,
    port: PortNumber = refineMV(9999),
    timeout: FiniteDuration = 5.seconds
  )

  case class PollingConfig(pollInterval: FiniteDuration = 2.seconds, errorThreshold: PosInt = PosInt(2))

  implicit def eq[F[_]]: Eq[TplinkDevice[F]] = Eq.by(_.name.value)

  def plug[F[_]: Sync](tplink: TplinkClient[F])(implicit errors: TplinkDeviceErrors[F]): F[SmartPlug[F]] =
    for {
      initialState <- tplink.getState
      state <- Ref.of(initialState)
    } yield
      new SmartPlug[F] {
        override def refresh: F[Unit] = tplink.getState.flatMap(state.set)
        override def name: NonEmptyString = tplink.deviceName
        override val device: NonEmptyString = NonEmptyString("HS100")
        override def getState: F[State] = state.get
        override def switchOn: F[Unit] =
          tplink.sendCommand(SwitchOnCommand).flatMap(tplink.parseSetResponse(SetRelayState)) *> state.set(State.On)
        override def switchOff: F[Unit] =
          tplink.sendCommand(SwitchOffCommand).flatMap(tplink.parseSetResponse(SetRelayState)) *> state.set(State.Off)
        override def rename(name: NonEmptyString, room: Option[Room]): F[Unit] =
          tplink.sendCommand(s"""{"$System":{"$SetDevAlias":"$name"}}""").flatMap(tplink.parseSetResponse(SetDevAlias))
      }

  case class BulbState(power: State, brightness: Int)

  def bulb[F[_]: Sync](deviceRoom: Room, tplink: TplinkClient[F])(
    implicit errors: TplinkDeviceErrors[F]
  ): F[SmartBulb[F]] = {
    def getBrightness: F[Int] = ???

    //   def _getState: F[BulbState] = tplink.getState

    for {
      initialState <- tplink.getState
      state <- Ref.of(initialState)
    } yield
      new SmartBulb[F] {
        override def refresh: F[Unit] = tplink.getState.flatMap(state.set)
        override def name: NonEmptyString = tplink.deviceName
        override val device: NonEmptyString = NonEmptyString("KL60")
        override def getState: F[State] = state.get
        override def switchOn: F[Unit] =
          tplink.sendCommand(SwitchOnCommand).flatMap(tplink.parseSetResponse(SetRelayState)) *> state.set(State.On)
        override def switchOff: F[Unit] =
          tplink.sendCommand(SwitchOffCommand).flatMap(tplink.parseSetResponse(SetRelayState)) *> state.set(State.Off)
        override def rename(name: NonEmptyString, room: Option[Room]): F[Unit] =
          tplink.sendCommand(s"""{"$System":{"$SetDevAlias":"$name"}}""").flatMap(tplink.parseSetResponse(SetDevAlias))
      }
  }
}
