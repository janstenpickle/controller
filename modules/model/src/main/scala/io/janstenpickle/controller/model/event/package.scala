package io.janstenpickle.controller.model

import io.circe.generic.extras.Configuration

package object event {
  implicit val circeConfig: Configuration = io.janstenpickle.controller.model.circeConfig

}
