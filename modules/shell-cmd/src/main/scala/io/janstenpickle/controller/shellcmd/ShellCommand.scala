package io.janstenpickle.controller.shellcmd

import eu.timepit.refined.types.string.NonEmptyString

case class ShellCommand(name: NonEmptyString, device: NonEmptyString, executable: NonEmptyString, successStatus: Int = 0)

case class Commands(shellCmds: List[ShellCommand])