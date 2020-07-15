package io.janstenpickle.controller.tplink

import cats.{Apply, FlatMap, Monad}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors}
import cats.syntax.apply._
import io.janstenpickle.controller.tplink.device.TplinkDevice
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.RemoteEvent
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.SpanStatus

object TplinkRemoteControl {
  def commands[F[_]](device: TplinkDevice.SmartBulb[F]): Map[NonEmptyString, F[Unit]] =
    (if (device.dimmable)
       Map(Commands.BrightnessUp -> device.brightnessUp, Commands.BrightnessDown -> device.brightnessDown)
     else Map.empty[NonEmptyString, F[Unit]]) ++ (
      if (device.colourTemp)
        Map(NonEmptyString("temp_up") -> device.tempUp, NonEmptyString("temp_down") -> device.tempDown)
      else Map.empty[NonEmptyString, F[Unit]]
    ) ++ (
      if (device.colourTemp)
        Map(
          NonEmptyString("hue_up") -> device.hueUp,
          NonEmptyString("hue_down") -> device.hueUp,
          NonEmptyString("saturation_up") -> device.saturationUp,
          NonEmptyString("saturation_down") -> device.saturationDown
        )
      else Map.empty[NonEmptyString, F[Unit]]
    )

  def apply[F[_]: Monad](
    remote: NonEmptyString,
    discovery: TplinkDiscovery[F],
    eventPublisher: EventPublisher[F, RemoteEvent]
  )(implicit trace: Trace[F], errors: RemoteControlErrors[F]): F[RemoteControl[F]] =
    RemoteControl
      .evented(
        RemoteControl.traced(new RemoteControl[F] {

          def devices: F[Map[(NonEmptyString, DeviceType), TplinkDevice.SmartBulb[F]]] =
            trace.span("tplink.list.devices") {
              discovery.devices
                .map(_.devices.collect {
                  case (k, v: TplinkDevice.SmartBulb[F]) => k -> v
                })
                .flatTap { devices =>
                  trace.put("device.count", devices.size)
                }
            }

          override def remoteName: NonEmptyString = remote

          override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
            trace.put("reason", "learning not supported") *> trace.setStatus(SpanStatus.Unimplemented) *> errors
              .learningNotSupported(remoteName)

          override def sendCommand(
            source: Option[RemoteCommandSource],
            deviceName: NonEmptyString,
            name: NonEmptyString
          ): F[Unit] =
            if (source == CommandSource)
              devices.flatMap(_.find(_._2.roomName == deviceName) match {
                case None =>
                  trace.setStatus(SpanStatus.NotFound) *> errors
                    .commandNotFound(remoteName, deviceName, name)
                case Some((_, device)) =>
                  commands(device).get(name) match {
                    case None => errors.commandNotFound(remoteName, deviceName, name)
                    case Some(command) => command
                  }

              })
            else errors.commandNotFound(remoteName, deviceName, name)

          override def listCommands: F[List[RemoteCommand]] = devices.map { devs =>
            devs.toList.flatMap {
              case (_, dev) =>
                commands(dev).keys.toList.map { command =>
                  RemoteCommand(remoteName, CommandSource, dev.roomName, command)
                }
            }

          }
        }),
        eventPublisher
      )
}
