import java.nio.ByteBuffer

import com.github.badoualy.telegram.api.TelegramApiStorage
import com.github.badoualy.telegram.mtproto.auth.AuthKey
import com.github.badoualy.telegram.mtproto.model.{DataCenter, MTSession}

import scala.collection.mutable.ArrayBuffer

object Session extends TelegramApiStorage with Serializable {
  var key: Option[AuthKey] = None
  var dc: Option[DataCenter] = None
  var session: Option[MTSession] = None

  override def loadAuthKey(): AuthKey = key.orNull

  override def loadDc(): DataCenter = dc.orNull

  override def loadSession(): MTSession = session.orNull

  override def deleteAuthKey(): Unit = key = None

  override def deleteDc(): Unit = dc = None

  override def saveAuthKey(key: AuthKey): Unit = this.key = Option(key)

  override def saveDc(dc: DataCenter): Unit = this.dc = Option(dc)

  override def saveSession(session: MTSession): Unit = this.session = Option(session)

  def serialize(): Seq[Byte] = {
    /*
      Serialized fields into a sequence of bytes
       1 byte for flags (key/dc/session present)
       if key present:
        4 bytes for the key length = n, n bytes for the key
       if dc present:
        4 bytes for the port, 4 bytes for the addr length = n, n bytes for the addr
     */
    val flags = ((if (key.isDefined) 1 else 0) |
      (if (dc.isDefined) 1 << 1 else 0) |
      (if (session.isDefined) 1 << 2 else 0)).toByte
    var output: ArrayBuffer[Byte] = ArrayBuffer(flags)

    key match {
      case Some(k) =>
        val bytes = k.getKey
        output ++= ByteBuffer.allocate(4 + bytes.length).putInt(bytes.length).put(bytes).array()
      case None =>
    }

    dc match {
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
  def deserialize(bytes: Seq[Byte]): Boolean = {
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

    this.key = key
    this.dc = dc
    true
  }
}
