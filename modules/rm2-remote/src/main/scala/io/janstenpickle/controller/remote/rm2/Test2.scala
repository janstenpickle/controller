package io.janstenpickle.controller.remote.rm2

import java.util.Base64

import com.github.mob41.blapi.BLDevice
import com.github.mob41.blapi.mac.Mac
import javax.xml.bind.DatatypeConverter

object Test2 extends App {
  val x = new BLDevice(BLDevice.DEV_RM_2, BLDevice.DESC_RM_2, "", new Mac("34:EA:34:B5:2E:D2")) {
    def decrypt(data: Array[Byte]) = decryptFromDeviceMessage(data)
  }

  println(
    DatatypeConverter.printHexBinary(
      x.decrypt(
        Base64.getDecoder.decode(
          "WqWqVVqlqlUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABK9wAAKidlAP0NNOo0tS7SAAAAAKHDAABFNFLn+S7alYNEkwg175pt+2ktw3C5BEOsXNY/u1Ot+giBTKf4z0FxADKOVww7hslNBXCESaOJ4prhBFQ2oFvd3ALBYa8TJeh+GbD30c4GjeUbYZFWh20zjP87mR5AzbE"
        )
      )
    )
  )
}
