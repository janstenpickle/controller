package io.janstenpickle.controller.events

package object syntax {
  object all extends EventSubscriberSyntax with StreamSyntax
  object subscriber extends EventSubscriberSyntax
  object stream extends StreamSyntax
}
