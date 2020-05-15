package io.janstenpickle.controller.homekit

import java.io.Closeable
import java.lang
import java.util.concurrent.CompletableFuture

import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Concurrent, ContextShift, Fiber, Resource, Timer}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Id}
import cats.syntax.applicativeError._
import eu.timepit.refined.auto._
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.hapjava.accessories.{Lightbulb, Outlet, Switch}
import io.github.hapjava.{HomekitAccessory, HomekitCharacteristicChangeCallback, HomekitRoot}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.Command.{SwitchOff, SwitchOn}
import io.janstenpickle.controller.model.event.{CommandEvent, SwitchEvent}
import io.janstenpickle.controller.model.{State, SwitchKey, SwitchMetadata, SwitchType}
import io.janstenpickle.controller.model.event.SwitchEvent.{
  SwitchAddedEvent,
  SwitchRemovedEvent,
  SwitchStateUpdateEvent
}
import natchez.{Trace, TraceValue}
import org.apache.commons.text.WordUtils

import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.hashing.MurmurHash3

object ControllerAccessories {
  def apply[F[_]: Timer: ContextShift, G[_]](
    root: HomekitRoot,
    switchEvents: EventSubscriber[F, SwitchEvent],
    commands: EventPublisher[F, CommandEvent],
    blocker: Blocker,
    fkFuture: F ~> Future,
    fk: F ~> Id
  )(implicit F: Concurrent[F], trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Unit] =
    Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
      def rootSpan[A](fa: F[A]): F[A] = liftLower.lift(liftLower.lower("homekit.accessories")(fa))

      def switchToService(
        key: SwitchKey,
        metadata: SwitchMetadata,
        getState: F[State],
        stateUpdates: Queue[F, State]
      ): HomekitAccessory with Closeable = {
        val model = metadata.model.getOrElse(key.device.value)
        val label = WordUtils.capitalizeFully(
          metadata.room
            .fold(s"${key.name.value} $model")(r => s"$r ${key.name.value}")
            .replace('_', ' ')
            .replace('-', ' ')
        )
        val id = math.abs(MurmurHash3.stringHash(metadata.id.getOrElse(s"${key.name}${key.device}")) + 1)
        val manufacturer = metadata.manufacturer.orNull

        def span[A](name: String, extraFields: (String, TraceValue)*)(k: F[A]): F[A] =
          trace.span(s"homekit.switch.$name") {
            trace.put(
              Seq[(String, TraceValue)](
                "device.id" -> id,
                "device.label" -> label,
                "device.model" -> model,
                "device.name" -> key.name.value,
                "device.type" -> key.device.value
              ) ++ extraFields: _*
            ) *> k
          }

        def switchState: CompletableFuture[lang.Boolean] =
          fkFuture(span("get.state") {
            getState
          }).map { state =>
              lang.Boolean.valueOf(state.isOn)
            }(blocker.blockingContext)
            .toJava
            .toCompletableFuture

        def setState(state: Boolean): CompletableFuture[Void] =
          fkFuture(span("set.state") {
            if (state) commands.publish1(CommandEvent.MacroCommand(SwitchOn(key.device, key.name)))
            else commands.publish1(CommandEvent.MacroCommand(SwitchOff(key.device, key.name)))
          }).map(_ => null.asInstanceOf[Void])(blocker.blockingContext).toJava.toCompletableFuture

        def subscribeUpdates(callback: HomekitCharacteristicChangeCallback) =
          span("subscribe") {
            blocker.blockOn(
              stateUpdates.dequeue
                .evalMap { _ =>
                  rootSpan(span("update.subscriber") {
                    Concurrent.timeout(blocker.delay(callback.changed()), 3.seconds).handleError { th =>
                      logger.error(th)(s"Failed to exec state callback for switch '${key.name}'") *> trace
                        .put("error" -> true, "error.message" -> th.getMessage)
                    }
                  })
                }
                .compile
                .drain
                .start
            )
          }

        def switch = new Switch with Closeable {
          private var switchChanges: Fiber[F, Unit] = _

          override lazy val getLabel: String = label
          override lazy val getId: Int = id
          override lazy val getSerialNumber: String = metadata.id.orNull
          override lazy val getModel: String = model
          override lazy val getManufacturer: String = manufacturer

          override def getSwitchState: CompletableFuture[lang.Boolean] = switchState
          override def setSwitchState(state: Boolean): CompletableFuture[Void] = setState(state)
          override def subscribeSwitchState(callback: HomekitCharacteristicChangeCallback): Unit =
            if (switchChanges == null) switchChanges = fk(subscribeUpdates(callback)) else ()
          override def unsubscribeSwitchState(): Unit =
            if (switchChanges != null) fk(span("unsubscribe") {
              switchChanges.cancel
            })
            else ()
          override def identify(): Unit = ()
          override def close(): Unit = unsubscribeSwitchState()
        }

        def bulb = new Lightbulb with Closeable {
          private var switchChanges: Fiber[F, Unit] = _

          override lazy val getLabel: String = label
          override lazy val getId: Int = id
          override lazy val getSerialNumber: String = metadata.id.orNull
          override lazy val getModel: String = model
          override lazy val getManufacturer: String = manufacturer

          override def getLightbulbPowerState: CompletableFuture[lang.Boolean] = switchState

          override def setLightbulbPowerState(powerState: Boolean): CompletableFuture[Void] = setState(powerState)

          override def subscribeLightbulbPowerState(callback: HomekitCharacteristicChangeCallback): Unit =
            if (switchChanges == null) switchChanges = fk(subscribeUpdates(callback)) else ()

          override def unsubscribeLightbulbPowerState(): Unit =
            if (switchChanges != null) fk(span("unsubscribe") {
              switchChanges.cancel
            })
            else ()

          override def identify(): Unit = ()
          override def close(): Unit = unsubscribeLightbulbPowerState()

        }

        def plug = new Outlet with Closeable {
          private var switchChanges: Fiber[F, Unit] = _

          override lazy val getLabel: String = label
          override lazy val getId: Int = id
          override lazy val getSerialNumber: String = metadata.id.orNull
          override lazy val getModel: String = model
          override lazy val getManufacturer: String = manufacturer

          override def getPowerState: CompletableFuture[lang.Boolean] = switchState

          override def getOutletInUse: CompletableFuture[lang.Boolean] =
            Future.successful(lang.Boolean.TRUE).toJava.toCompletableFuture

          override def setPowerState(state: Boolean): CompletableFuture[Void] = setState(state)

          override def subscribePowerState(callback: HomekitCharacteristicChangeCallback): Unit =
            if (switchChanges == null) switchChanges = fk(subscribeUpdates(callback)) else ()

          override def subscribeOutletInUse(callback: HomekitCharacteristicChangeCallback): Unit = ()

          override def unsubscribePowerState(): Unit =
            if (switchChanges != null) fk(span("unsubscribe") {
              switchChanges.cancel
            })
            else ()

          override def unsubscribeOutletInUse(): Unit = ()

          override def identify(): Unit = ()
          override def close(): Unit = unsubscribePowerState()
        }

        metadata.`type` match {
          case SwitchType.Bulb => bulb
          case SwitchType.Plug => plug
          case _ => switch
        }
      }

      def stream(
        switchRef: Ref[F, Map[SwitchKey, (HomekitAccessory with Closeable, Queue[F, State])]],
        stateRef: Ref[F, Map[SwitchKey, State]]
      ) =
        switchEvents.subscribe.evalMap {
          case SwitchAddedEvent(key, meta) =>
            Queue.unbounded[F, State].flatMap { queue =>
              val accessory = switchToService(key, meta, stateRef.get.map(_.getOrElse(key, State.Off)), queue)

              switchRef.update(_.updated(key, (accessory, queue))) *> F.delay(root.addAccessory(accessory))
            }
          case SwitchRemovedEvent(key) =>
            for {
              state <- switchRef.get
              _ <- switchRef.set(state - key)
              _ <- stateRef.update(_ - key)
              _ <- F.delay(state.get(key).fold(()) { case (accessory, _) => root.removeAccessory(accessory) })
            } yield ()
          case SwitchStateUpdateEvent(key, state, None) =>
            stateRef.update(_.updated(key, state)) *> switchRef.get.flatMap(_.get(key).fold(F.unit) {
              case (_, queue) => queue.enqueue1(state)
            })
          case _ =>
            F.unit
        }

      for {
        ref <- Resource.make(Ref.of(Map.empty[SwitchKey, (HomekitAccessory with Closeable, Queue[F, State])]))(
          ref => ref.get.flatMap(sws => F.delay(sws.values.foreach(_._1.close())))
        )
        stateRef <- Resource.liftF(Ref.of(Map.empty[SwitchKey, State]))
        _ <- blocker.blockOn(stream(ref, stateRef).compile.drain).background
      } yield ()
    }
}
