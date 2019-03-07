package io.janstenpickle.controller.sonos

import cats.Functor
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{State, Switch, SwitchProvider}

object SonosSwitchProvider {

  def apply[F[_]: Functor](deviceName: NonEmptyString, discovery: SonosDiscovery[F]): SwitchProvider[F] =
    new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
        discovery.devices.map(_.map {
          case (_, dev) =>
            (SwitchKey(deviceName, dev.name), new Switch[F] {
              override def name: NonEmptyString = dev.name
              override def device: NonEmptyString = deviceName
              override def getState: F[State] = dev.isPlaying.map(if (_) State.On else State.Off)
              override def switchOn: F[Unit] = dev.play
              override def switchOff: F[Unit] = dev.pause
            })
        })
    }
}
