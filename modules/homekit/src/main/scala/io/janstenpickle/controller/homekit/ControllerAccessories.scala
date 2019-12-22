package io.janstenpickle.controller.homekit

import java.io.Closeable
import java.lang
import java.util.concurrent.CompletableFuture

import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Concurrent, ContextShift, Fiber, Resource, Timer}
import cats.instances.set._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{~>, Id}
import cats.syntax.applicativeError._
import eu.timepit.refined.auto._
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.hapjava.accessories.Switch
import io.github.hapjava.{HomekitCharacteristicChangeCallback, HomekitRoot}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switch.model.SwitchKey
import natchez.{Trace, TraceValue}

import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.hashing.MurmurHash3

object ControllerAccessories {
  case class SwitchState(
    state: Map[SwitchKey, Switch with Closeable],
    toAdd: Iterable[Switch with Closeable],
    toRemove: Iterable[Switch with Closeable]
  )

  def apply[F[_]: Timer: ContextShift, G[_]](
    root: HomekitRoot,
    switches: Switches[F],
    switchUpdate: Stream[F, SwitchKey],
    blocker: Blocker,
    fkFuture: F ~> Future,
    fk: F ~> Id
  )(implicit F: Concurrent[F], trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Unit] =
    Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
      def rootSpan[A](fa: F[A]): F[A] = liftLower.lift(liftLower.lower("homekit")(fa))

      def switchToService(key: SwitchKey): Switch with Closeable = new Switch with Closeable {

        override lazy val getLabel: String = s"${key.name} ${key.device}".replace('_', ' ').replace('-', ' ')
        override lazy val getId: Int = math.abs(MurmurHash3.stringHash(getLabel) + 1)
        override lazy val getSerialNumber: String = null
        override lazy val getModel: String = key.device
        override lazy val getManufacturer: String = null

        private def span[A](name: String, extraFields: (String, TraceValue)*)(k: F[A]): F[A] =
          trace.span(s"homekit.switch.$name") {
            trace.put(
              Seq[(String, TraceValue)](
                "device.id" -> getId,
                "device.label" -> getLabel,
                "device.model" -> getModel,
                "device.name" -> key.name.value,
                "device.type" -> key.device.value
              ) ++ extraFields: _*
            ) *> k
          }

        private var switchChanges: Fiber[F, Unit] = null

        override def getSwitchState: CompletableFuture[lang.Boolean] =
          fkFuture(span("get.state") {
            switches.getState(key.device, key.name)
          }).map { state =>
              lang.Boolean.valueOf(state.isOn)
            }(blocker.blockingContext)
            .toJava
            .toCompletableFuture

        override def setSwitchState(state: Boolean): CompletableFuture[Void] =
          fkFuture(span("set.state") {
            if (state) switches.switchOn(key.device, key.name) else switches.switchOff(key.device, key.name)
          }).map(_ => null.asInstanceOf[Void])(blocker.blockingContext).toJava.toCompletableFuture

        override def subscribeSwitchState(callback: HomekitCharacteristicChangeCallback): Unit =
          if (switchChanges == null)
            switchChanges = fk(span("subscribe") {
              blocker.blockOn(
                switchUpdate
                  .evalMap { k =>
                    if (k == key) rootSpan(span("update.subscriber") {
                      Concurrent.timeout(blocker.delay(callback.changed()), 3.seconds).handleError { th =>
                        logger.error(th)(s"Failed to exec state callback for switch '${key.name}'") *> trace
                          .put("error" -> true, "error.message" -> th.getMessage)
                      }
                    })
                    else F.unit
                  }
                  .compile
                  .drain
                  .start
              )
            })
          else ()

        override def unsubscribeSwitchState(): Unit =
          if (switchChanges != null) fk(span("unsubscribe") {
            switchChanges.cancel
          })
          else ()

        override def identify(): Unit = ()

        override def close(): Unit = unsubscribeSwitchState()
      }

      def diffSwitches(map: Map[SwitchKey, Switch with Closeable]): F[SwitchState] =
        switches.list.map { sws =>
          val newSwitches = sws
            .filterNot(map.contains)
            .map { key =>
              key -> switchToService(key)
            }
            .toMap

          SwitchState(
            map.filterKeys(sws.contains) ++ newSwitches,
            newSwitches.values,
            map.filterKeys(!sws.contains(_)).values
          )
        }

      def stream(ref: Ref[F, Map[SwitchKey, Switch with Closeable]]): Stream[F, Unit] =
        Stream
          .fixedRate(30.seconds)
          .evalMap(
            _ =>
              rootSpan(trace.span("homekit.update.switches") {
                for {
                  data <- ref.get
                  switches <- diffSwitches(data)
                  _ <- ref.set(switches.state)
                  toAddSize = switches.toAdd.size
                  toRemoveSize = switches.toRemove.size
                  _ <- if (toAddSize > 0) logger.info(s"Adding ${switches.toAdd.size} switches") else F.unit
                  _ <- if (toRemoveSize > 0) logger.info(s"Removing ${switches.toRemove.size} switches") else F.unit
                  _ <- trace.put(
                    "switches.count" -> switches.state.size,
                    "switches.to.add" -> toAddSize,
                    "switches.to.remove" -> toRemoveSize
                  )
                  _ <- F.delay(switches.toAdd.foreach(root.addAccessory))
                  _ <- F.delay(switches.toRemove.foreach(root.removeAccessory))
                  _ <- F.delay(switches.toRemove.foreach(_.close))
                } yield ()
              })
          )
          .handleErrorWith { th =>
            Stream.eval(logger.error(th)("Homekit Accessory Updater failed, restarting")) >> stream(ref)
          }

      for {
        init <- Resource.liftF(diffSwitches(Map.empty))
        _ <- Resource.liftF(F.delay(init.toAdd.foreach(root.addAccessory)))
        ref <- Resource.make(Ref.of(init.state))(ref => ref.get.flatMap(sws => F.delay(sws.values.foreach(_.close()))))
        _ <- Resource.make(blocker.blockOn(stream(ref).compile.drain.start))(_.cancel)
      } yield ()
    }
}
