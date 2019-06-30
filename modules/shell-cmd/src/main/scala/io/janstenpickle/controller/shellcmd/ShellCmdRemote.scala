package io.janstenpickle.controller.shellcmd

import cats.Applicative
import cats.effect.{ContextShift, Sync}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.remote.Remote
import io.janstenpickle.controller.remotecontrol.RemoteControl
import io.janstenpickle.controller.store.{RemoteCommand, RemoteCommandStore}
import eu.timepit.refined._

import scala.concurrent.ExecutionContext

import sys.process._


object ShellCmdRemote {
  def apply[F[_]](
    store: RemoteCommandStore[F, ShellCommand],
    ec: ExecutionContext
  )(implicit F: Sync[F], cs: ContextShift[F]) =
    new Remote[F, ShellCommand] {
      override def name: NonEmptyString = refineMV("shell")

      override def learn: F[Option[ShellCommand]] = ???

      override def sendCommand(payload: ShellCommand): F[Unit] = ???
    }
}
