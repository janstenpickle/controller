package io.janstenpickle.control.switch.polling

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.switch.State

trait PollingSwitchErrors[F[_]] {
  def pollError[A](switch: NonEmptyString, value: State, lastUpdated: Long, error: Throwable): F[A]
}
