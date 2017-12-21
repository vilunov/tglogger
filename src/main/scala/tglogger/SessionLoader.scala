package tglogger

import scala.collection.mutable.ArrayBuffer
import java.io.{File, FileOutputStream}
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}

import com.github.badoualy.telegram.mtproto.auth.AuthKey
import com.github.badoualy.telegram.mtproto.model.DataCenter

import Vars.TgClient.SessionFilePath

object SessionLoader {
  /**
    * Reads the file with session information
    */
  def loadSession()(implicit session: TgSession): Unit = {
    if (new File(SessionFilePath).exists()) {
      val s = Files.readAllBytes(Paths.get(SessionFilePath))
      deserialize(s)
    }
  }

  /**
    * Saves the session information into the file
    */
  def saveSession()(implicit session: TgSession): Unit = {
    val fos = new FileOutputStream(SessionFilePath)
    try {
      fos.write(serialize().toArray)
    } finally {
      fos.close()
    }
  }

  def serialize()(implicit session: TgSession): Seq[Byte] = {
    /*
      Serialized fields into a sequence of bytes
       1 byte for flags (key/dc/session present)
       if key present:
        4 bytes for the key length = n, n bytes for the key
       if dc present:
        4 bytes for the port, 4 bytes for the addr length = n, n bytes for the addr
     */
    val flags = ((if (session.key.isDefined) 1 else 0) |
      (if (session.dc.isDefined) 1 << 1 else 0) |
      (if (session.session.isDefined) 1 << 2 else 0)).toByte
    var output: ArrayBuffer[Byte] = ArrayBuffer(flags)

    session.key match {
      case Some(k) =>
        val bytes = k.getKey
        output ++= ByteBuffer.allocate(4 + bytes.length).putInt(bytes.length).put(bytes).array()
      case None =>
    }

    session.dc match {
      case Some(k) =>
        val bytes = k.getIp.getBytes()
        output ++= ByteBuffer.allocate(8 + bytes.length).putInt(k.getPort).putInt(bytes.length).put(bytes).array()
      case None =>
    }

    output
  }

  /**
    * Parses the sequence of bytes and restores original values
    *
    * @param bytes sequence of bytes returned previously by the `serialize` method
    * @return true on success, false on failure
    */
  def deserialize(bytes: Seq[Byte])(implicit session: TgSession): Boolean = {
    if (bytes.isEmpty) return false
    var input = bytes

    val hasKey = (input.head & 1) != 0
    val hasDc = (input.head & (1 << 1)) != 0
    val hasSession = (input.head & (1 << 2)) != 0
    input = bytes.tail

    val key = if (hasKey) {
      if (input.lengthCompare(4) < 0) return false
      val length = ByteBuffer.wrap(input.slice(0, 4).toArray).getInt

      if (input.lengthCompare(4 + length) < 0) return false
      val key = ByteBuffer.wrap(input.slice(4, 4 + length).toArray).array()

      input = input.slice(4 + length, input.length)
      Option(new AuthKey(key))
    } else None

    val dc = if (hasDc) {
      if (input.lengthCompare(8) < 0) return false
      val buf = ByteBuffer.wrap(input.slice(0, 8).toArray)
      val port = buf.getInt
      val length = buf.getInt

      if (input.lengthCompare(8 + length) < 0) return false
      val ip = new String(input.slice(8, 8 + length).toArray)

      input = input.slice(8 + length, input.length)
      Option(new DataCenter(ip, port))
    } else None

    session.key = key
    session.dc = dc
    true
  }
}
