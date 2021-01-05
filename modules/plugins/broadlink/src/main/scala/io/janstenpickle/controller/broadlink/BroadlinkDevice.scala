package io.janstenpickle.controller.broadlink

import cats.{Applicative, Eq}
import cats.syntax.functor._
import io.janstenpickle.controller.broadlink.remote.RmRemote
import io.janstenpickle.controller.broadlink.switch.SpSwitch
import io.janstenpickle.controller.discovery.DiscoveredDevice
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

sealed trait BroadlinkDevice[F[_]] extends DiscoveredDevice[F]

object BroadlinkDevice {
  case class Remote[F[_]: Applicative](remote: RmRemote[F]) extends BroadlinkDevice[F] {
    override def key: DiscoveredDeviceKey = DiscoveredDeviceKey(formatDeviceId(remote.mac), devType("remote"))
    override def value: DiscoveredDeviceValue = DiscoveredDeviceValue(remote.name, None)
    override def refresh: F[Unit] = Applicative[F].unit
    override def updatedKey: F[String] = Applicative[F].pure(remote.name.value)
  }

  case class Switch[F[_]: Applicative](switch: SpSwitch[F]) extends BroadlinkDevice[F] {
    override def key: DiscoveredDeviceKey = DiscoveredDeviceKey(formatDeviceId(switch.mac), devType("switch"))
    override def value: DiscoveredDeviceValue = DiscoveredDeviceValue(switch.name, None)
    override def refresh: F[Unit] = switch.refresh
    override def updatedKey: F[String] = switch.getState.map { state =>
      s"${switch.name}${state.isOn}"
    }
  }

  implicit def broadlinkDeviceEq[F[_]]: Eq[BroadlinkDevice[F]] = Eq.instance {
    case (Remote(x), Remote(y)) => Eq.eqv(x, y)
    case (Switch(x), Switch(y)) => Eq.eqv(x, y)
    case (_, _) => false
  }
}
