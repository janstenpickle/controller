package io.janstenpickle.controller.broadlink.remote

import cats.{Applicative, Parallel}
import cats.effect.{Async, ContextShift, Sync, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.broadlink.{BroadlinkDevice, BroadlinkDiscovery}
import io.janstenpickle.controller.model.{CommandPayload, RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors, RemoteControls}
import io.janstenpickle.controller.store.RemoteCommandStore
import natchez.Trace
import cats.syntax.flatMap._
import cats.instances.list._
import cats.syntax.traverse._
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.RemoteEvent
import io.janstenpickle.controller.remote.trace.TracedRemote
import scalacache.Cache
import scalacache.CatsEffect.modes._
import scalacache.memoization.memoizeF

object RmRemoteControls {
  def apply[F[_]: Async: ContextShift: Timer: Parallel: RemoteControlErrors: Trace](
    discovery: BroadlinkDiscovery[F],
    store: RemoteCommandStore[F, CommandPayload],
    cache: Cache[RemoteControls[F]],
    eventPublisher: EventPublisher[F, RemoteEvent]
  ): RemoteControls[F] =
    new RemoteControls[F] {
      private implicit def _cache = cache

      private def underlying: F[RemoteControls[F]] = memoizeF(None) {
        discovery.devices.flatMap(
          d =>
            d.devices.toList
              .flatTraverse[F, (NonEmptyString, RemoteControl[F])] {
                case (_, BroadlinkDevice.Remote(remote)) =>
                  RemoteControl(
                    TracedRemote(remote, "host" -> remote.host, "mac" -> remote.mac, "manufacturer" -> "broadlink"),
                    store,
                    eventPublisher
                  ).map(r => List(remote.name -> r))

                case _ => Applicative[F].pure(List.empty)
              }
              .map(remotes => RemoteControls(remotes.toMap))
        )
      }

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
