package io.janstenpickle.controller.tplink

object Constants {
  final val System = "system"
  final val GetSysInfo = "get_sysinfo"
  final val InfoCommand = s""""$System":{"$GetSysInfo":null}"""
  final val SetDevAlias = "set_dev_alias"

}
