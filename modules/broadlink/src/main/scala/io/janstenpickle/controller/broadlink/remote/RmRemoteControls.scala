package io.janstenpickle.controller.broadlink.remote

import cats.Parallel
import cats.effect.{ContextShift, Sync, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.broadlink.BroadlinkDiscovery
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors, RemoteControls}
import io.janstenpickle.controller.store.RemoteCommandStore
import natchez.Trace
import cats.syntax.flatMap._
import io.janstenpickle.controller.remote.trace.TracedRemote

object RmRemoteControls {
  def apply[F[_]: Sync: ContextShift: Timer: Parallel: RemoteControlErrors: Trace](
    discovery: BroadlinkDiscovery[F],
    store: RemoteCommandStore[F, CommandPayload],
  ): RemoteControls[F] =
    new RemoteControls[F] {
      // TODO cache?
      private def underlying =
        discovery.devices.map(
          d =>
            RemoteControls(d.devices.collect {
              case (name, Right(remote)) =>
                name -> RemoteControl.traced(
                  RemoteControl(
                    TracedRemote(remote, "host" -> remote.host, "mac" -> remote.mac, "manufacturer" -> "broadlink"),
                    store
                  )
                )
            })
        )

      override def send(
        remote: NonEmptyString,
        commandSource: Option[RemoteCommandSource],
        device: NonEmptyString,
        name: NonEmptyString
      ): F[Unit] = underlying.flatMap(_.send(remote, commandSource, device, name))

      override def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        underlying.flatMap(_.learn(remote, device, name))

      override def listCommands: F[List[RemoteCommand]] =
        underlying.flatMap(_.listCommands)

      override def provides(remote: NonEmptyString): F[Boolean] = underlying.flatMap(_.provides(remote))
    }
}
