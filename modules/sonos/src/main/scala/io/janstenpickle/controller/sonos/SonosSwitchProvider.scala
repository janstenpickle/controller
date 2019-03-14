package io.janstenpickle.controller.sonos

import cats.effect.Async
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import scalacache.Cache
import scalacache.CatsEffect.modes._

object SonosSwitchProvider {
  def apply[F[_]: Async](
    deviceName: NonEmptyString,
    discovery: SonosDiscovery[F],
    cache: Cache[State]
  ): SwitchProvider[F] =
    new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
        discovery.devices.map(_.flatMap {
          case (_, dev) =>
            Map((SwitchKey(deviceName, dev.name), new Switch[F] {
              private val cacheKey: String = s"${device.value}_${name.value}"

              override def name: NonEmptyString = dev.name

              override def device: NonEmptyString = deviceName

              override def getState: F[State] =
                cache.cachingForMemoizeF[F](cacheKey)(None)(dev.isPlaying.map(if (_) State.On else State.Off))

              override def switchOn: F[Unit] = dev.play

              override def switchOff: F[Unit] = dev.pause
            }), (SwitchKey(deviceName, NonEmptyString.unsafeFrom(s"${dev.name.value}_group")), new Switch[F] {
              private val cacheKey: String = s"${device.value}_${name.value}"

              override def name: NonEmptyString = dev.name

              override def device: NonEmptyString = NonEmptyString.unsafeFrom(s"${dev.name.value}_group")

              override def getState: F[State] =
                cache.cachingForMemoizeF[F](cacheKey)(None)(dev.isGrouped.map(if (_) State.On else State.Off))

              override def switchOn: F[Unit] = dev.group

              override def switchOff: F[Unit] = dev.unGroup
            }))
        })
    }
}
