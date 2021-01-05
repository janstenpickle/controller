package io.janstenpickle.controller

import cats.Order
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Button
import io.janstenpickle.controller.model.Button.{Context, Macro, Remote, Switch}

package object stats {
  implicit val nesOrder: Order[NonEmptyString] = Order.by(_.value)

  val buttonType: Button => NonEmptyString = {
    case _: Remote => NonEmptyString("remote")
    case _: Switch => NonEmptyString("switch")
    case _: Macro => NonEmptyString("macro")
    case _: Context => NonEmptyString("context")
  }
}
