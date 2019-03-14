package io.janstenpickle.controller.sonos

import java.util.concurrent.TimeUnit

import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.configsource.{ActivityConfigSource, RemoteConfigSource}
import io.janstenpickle.controller.model.{Remotes, State}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors}
import io.janstenpickle.controller.sonos.config.{SonosActivityConfigSource, SonosRemoteConfigSource}
import io.janstenpickle.controller.switch.SwitchProvider
import org.cache2k.Cache2kBuilder
import scalacache.CatsEffect.modes._
import scalacache.cache2k.Cache2kCache

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class SonosComponents[F[_]] private (
  remote: RemoteControl[F],
  activityConfig: ActivityConfigSource[F],
  remoteConfig: RemoteConfigSource[F],
  switches: SwitchProvider[F]
)

object SonosComponents {
  case class Config(
    activity: SonosActivityConfigSource.Config,
    remoteName: Option[NonEmptyString],
    combinedDeviceName: Option[NonEmptyString],
    switchDeviceName: Option[NonEmptyString],
    polling: SonosDiscovery.Polling,
    commandTimeout: FiniteDuration = 5.seconds,
    switchCacheTimeout: FiniteDuration = 500.millis,
    remotesCacheTimeout: FiniteDuration = 2.seconds,
    allRooms: Boolean = true
  ) {
    lazy val remote: NonEmptyString = remoteName.getOrElse(activity.remoteName)
    lazy val combinedDevice: NonEmptyString = combinedDeviceName.getOrElse(remote)
    lazy val switchDevice: NonEmptyString = switchDeviceName.getOrElse(remote)
    lazy val activityConfig: SonosActivityConfigSource.Config =
      activity.copy(remoteName = remote, combinedDeviceName = combinedDevice)
  }

  def apply[F[_]: ContextShift: Timer](
    config: Config,
    onUpdate: Map[NonEmptyString, SonosDevice[F]] => F[Unit],
    ec: ExecutionContext,
    onDeviceUpdate: () => F[Unit],
  )(implicit F: Concurrent[F], errors: RemoteControlErrors[F]): Resource[F, SonosComponents[F]] = {
    def makeCache[V](timeout: FiniteDuration, keyClass: Class[V]): Resource[F, Cache2kCache[V]] =
      Resource.make(
        suspendErrors(
          Cache2kCache(
            Cache2kBuilder
              .of[String, V](classOf[String], keyClass)
              .expireAfterWrite(timeout.toMillis, TimeUnit.MILLISECONDS)
              .build()
          )
        )
      )(c => F.suspend(c.close()).void)

    for {
      switchCache <- makeCache[State](config.switchCacheTimeout, classOf)

      remotesCache <- makeCache[Remotes](config.remotesCacheTimeout, classOf)

      discovery <- SonosDiscovery.polling[F](config.polling, config.commandTimeout, onUpdate, ec, onDeviceUpdate)
    } yield {
      val remote = SonosRemoteControl[F](config.remote, config.combinedDevice, discovery)
      val activityConfig = SonosActivityConfigSource[F](config.activity, discovery)
      val remoteConfig =
        SonosRemoteConfigSource[F](config.remote, config.activity.name, config.allRooms, discovery, remotesCache)
      val switches = SonosSwitchProvider[F](config.switchDevice, discovery, switchCache)

      SonosComponents(remote, activityConfig, remoteConfig, switches)
    }
  }

}
