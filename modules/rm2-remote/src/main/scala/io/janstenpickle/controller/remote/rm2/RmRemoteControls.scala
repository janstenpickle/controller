package io.janstenpickle.controller.remote.rm2

import cats.effect.{ContextShift, Sync, Timer}
import io.janstenpickle.controller.model.CommandPayload
import io.janstenpickle.controller.store.RemoteCommandStore
import cats.syntax.traverse._
import cats.instances.list._
import cats.syntax.functor._
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors, RemoteControls}

import scala.concurrent.ExecutionContext

object RmRemoteControls {
  def apply[F[_]: Sync: ContextShift: Timer: RemoteControlErrors](
    config: List[RmRemoteConfig],
    store: RemoteCommandStore[F, CommandPayload],
    ec: ExecutionContext
  ): F[RemoteControls[F]] =
    config.traverse(RmRemote[F](_, ec).map(r => r.name -> RemoteControl(r, store))).map { remotes =>
      RemoteControls(remotes.toMap)
    }
}
