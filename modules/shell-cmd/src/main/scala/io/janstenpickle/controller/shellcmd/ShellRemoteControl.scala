package io.janstenpickle.controller.shellcmd

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.remotecontrol.RemoteControl
import io.janstenpickle.controller.store.{RemoteCommand, RemoteCommandStore}

object ShellRemoteControl {
  def apply[F[_]](store: RemoteCommandStore[F, ShellCommand]) =  RemoteControl(???, store)
}
