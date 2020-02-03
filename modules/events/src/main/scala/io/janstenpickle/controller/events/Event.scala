package io.janstenpickle.controller.events

import java.time.Instant

case class Event[A](value: A, time: Instant)
