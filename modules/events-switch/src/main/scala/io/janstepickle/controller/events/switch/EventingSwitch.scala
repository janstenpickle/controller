package io.janstepickle.controller.events.switch

import cats.ApplicativeError
import cats.effect.Clock
import cats.syntax.applicativeError._
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.{State, SwitchKey, SwitchMetadata}
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.switch.Switch

object EventingSwitch {
  def apply[F[_]: ApplicativeError[*[_], Throwable]: Clock](
    underlying: Switch[F],
    publisher: EventPublisher[F, SwitchStateUpdateEvent]
  ): Switch[F] =
    new Switch[F] {
      private def publishState(state: State, error: Option[Throwable] = None) =
        publisher.publish1(SwitchStateUpdateEvent(SwitchKey(device, name), state, error))

      override def name: NonEmptyString = underlying.name

      override def device: NonEmptyString = underlying.device

      override def metadata: SwitchMetadata = underlying.metadata

      override def getState: F[State] = underlying.getState

      override def switchOn: F[Unit] = (underlying.switchOn *> publishState(State.On)).handleErrorWith { th =>
        publishState(State.On, Some(th)) *> th.raiseError
      }

      override def switchOff: F[Unit] = (underlying.switchOff *> publishState(State.Off)).handleErrorWith { th =>
        publishState(State.Off, Some(th)) *> th.raiseError
      }
    }
}
