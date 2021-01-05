package io.janstenpickle.controller

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{MultiSwitch, SwitchMetadata, SwitchType}

package object multiswitch {
  final val DeviceName: NonEmptyString = NonEmptyString("multi")

  def makeMetadata(switch: MultiSwitch): SwitchMetadata =
    SwitchMetadata(room = switch.room.map(_.value), `type` = SwitchType.Multi)
}
