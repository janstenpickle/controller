package io.janstenpickle.controller.api

import com.typesafe.config.ConfigFactory
import extruder.refined._
import extruder.typesafe._
import io.janstenpickle.controller.model.{Button, Buttons}

object Test extends App {
  val config = ConfigFactory.parseString(
    """ { buttons: [ {"type": "RemoteButtonIcon", "device":"mac","icon":"up","name":"up","remote":"lounge"} ] }"""
  )

  println(decode[Buttons](config))
}
