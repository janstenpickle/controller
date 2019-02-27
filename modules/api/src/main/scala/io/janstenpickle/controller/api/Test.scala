package io.janstenpickle.controller.api

import com.typesafe.config.ConfigFactory
import extruder.refined._
import extruder.circe.yaml._
import io.janstenpickle.controller.model.{Button, Buttons}

object Test extends App {
  val config = ConfigFactory.parseString(
    """ { buttons: [ {"type": "RemoteButtonIcon", "device":"mac","icon":"up","name":"up","remote":"lounge"} ] }"""
  )

  val yaml =
    """
      |---
      |remotes: []
      |buttons:
      | - type: RemoteIcon
      |   remote: lounge
      |   device: mac
      |   name: up
      |   icon: up
      | - type: RemoteIcon
      |   remote: lounge
      |   device: mac
      |   name: down
      |   icon: down
      |
      |activities: []
    """.stripMargin

  println(decode[Buttons](yaml))
}
