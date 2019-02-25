package io.janstenpickle.controller.switch.hs100

import java.io.InputStream
import java.nio.ByteBuffer

import cats.effect.Sync
import io.janstenpickle.catseffect.CatsEffect._

import scala.collection.mutable.ArrayBuffer

object Encryption {
  private val encryptKey: Int = 0xAB
  private val decryptKey: Int = 0x2B

  def encrypt(command: String): Array[Int] =
    command.toCharArray
      .foldLeft((encryptKey, Array.empty[Int])) {
        case ((key, data), char) =>
          val newKey = char ^ key
          (newKey, data :+ newKey)
      }
      ._2

  def encryptWithHeader(command: String): Array[Byte] = {
    val data = encrypt(command)
    val header = ByteBuffer.allocate(4).putInt(command.length()).array()
    val buffer = ByteBuffer.allocate(header.length + data.length).put(header)
    data.foreach { i =>
      buffer.put(i.toByte)
    }

    buffer.array()
  }

  def decrypt[F[_]](inputStream: InputStream)(implicit F: Sync[F]): F[String] =
    suspendErrors {
      val buffer = ArrayBuffer.newBuilder[Byte].result()
      var continue = true

      while (continue) {
        val array: Array[Byte] = Array.fill(4096)(1)
        inputStream.read(array)
        if (array.last == 1 || array.last == -1) {
          buffer.appendAll(array.dropRight(1).filterNot(_ == 1))
          continue = false
        } else buffer.appendAll(array)
      }

      val response = buffer
        .foldLeft((decryptKey, new StringBuilder)) {
          case ((key, sb), byte) =>
            (byte.toInt, sb.append((byte ^ key).asInstanceOf[Char]))
        }
        ._2
        .toString()

      "{" + response.substring(5)
    }

}
