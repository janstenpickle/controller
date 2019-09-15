package io.janstenpickle.controller.broadlink.remote

import cats.Parallel
import cats.effect.{Blocker, ContextShift, Sync, Timer}
import cats.instances.list._
import cats.syntax.functor._
import cats.syntax.parallel._
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors, RemoteControls}
import io.janstenpickle.controller.store.RemoteCommandStore
import natchez.Trace

object RmRemoteControls {
  def apply[F[_]: Sync: ContextShift: Timer: Parallel: RemoteControlErrors: Trace](
    config: List[RmRemoteConfig],
    store: RemoteCommandStore[F, CommandPayload],
    blocker: Blocker
  ): F[RemoteControls[F]] =
    config.parTraverse(RmRemote[F](_, blocker).map(r => r.name -> RemoteControl(r, store))).map { remotes =>
      RemoteControls(remotes.toMap)
    }
}
