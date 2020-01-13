package io.janstenpickle.control.switch.polling

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.errors.ErrorHandler
import io.janstenpickle.controller.model.State

trait PollingSwitchErrors[F[_]] { self: ErrorHandler[F] =>
  def pollError[A](switch: NonEmptyString, value: State, lastUpdated: Long, error: Throwable): F[A]
}
