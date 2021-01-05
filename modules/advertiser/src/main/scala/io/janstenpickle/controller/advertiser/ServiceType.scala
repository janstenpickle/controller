package io.janstenpickle.controller.advertiser

sealed trait ServiceType {
  protected val value: String

  override def toString: String = s"_$value._tcp.local."
}

object ServiceType {
  case object Coordinator extends ServiceType {
    override protected val value: String = "controller"
  }
  case object Client extends ServiceType {
    override protected val value: String = "controller-client"
  }
  case object Plugin extends ServiceType {
    override protected val value: String = "controller-plugin"
  }
}
