package io.janstenpickle.controller.sonos

import cats.Applicative
import eu.timepit.refined.types.string.NonEmptyString

trait SimpleSonosDevice[F[_]] {
  def applicative: Applicative[F]

  def name: NonEmptyString
  def play: F[Unit]
  def pause: F[Unit]
  def playPause: F[Unit]
  def volumeUp: F[Unit]
  def volumeDown: F[Unit]
  def mute: F[Unit]
  def unMute: F[Unit]
  def next: F[Unit]
  def previous: F[Unit]
  def isController: F[Boolean] = applicative.pure(true)
}
