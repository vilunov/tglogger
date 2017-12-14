import scala.collection.mutable

import com.github.badoualy.telegram.api.{Kotlogram, TelegramApp, TelegramClient}
import com.github.badoualy.telegram.tl.api.auth.TLAuthorization
import com.github.badoualy.telegram.tl.api._
import com.github.badoualy.telegram.tl.exception.RpcErrorException
import com.github.badoualy.telegram.tl.core.TLIntVector

import Vars.TgClient._
import TgHandler._

class TgHandler(val session: TgSession) {
  val app = new TelegramApp(ApiId, ApiHash, SessionName, "1", "1", "en")
  val client: TelegramClient = Kotlogram.getDefaultClient(app, session)
  private val chans: mutable.HashMap[Int, TLChannel] = mutable.HashMap()

  /**
    * If needed, ask user for login inputs and authenticate them
    */
  def auth(): Unit = {
    import scala.io.StdIn.readLine

    if (session.key.isDefined) return

    print("Input phone number: ")
    val phone = readLine()

    val sentCode = client.authSendCode(false, phone, true)
    print("Input authentication code: ")
    val code = readLine()

    val auth: TLAuthorization = try
      client.authSignIn(phone, sentCode.getPhoneCodeHash, code)
    catch {
      case e: RpcErrorException if e.getTag == "SESSION_PASSWORD_NEEDED" => {
        // Login failed due to two-step authorization
        // Ask user for password and try again
        print("Insert password: ")
        val pwd = readLine()
        client.authCheckPassword(pwd)
      }
    }

    val self = auth.getUser.getAsUser
    println(s"You are signed in as ${self.getFirstName} ${self.getLastName}")
  }

  /**
    * Update the list of available channels
    */
  def updateChannels(): Unit = {
    val channels = client.messagesGetAllChats(new TLIntVector).getChats.toArray().toSeq
      .collect { case m: TLChannel if !m.getMegagroup => m }
    channels foreach { chan =>
      chans.put(chan.getId, chan)
    }
  }

  def getChannels: Iterable[Int] =
    chans.keys

  /**
    * Get messages from a channel
    *
    * @param chanId channel to query messages from
    * @param minId the returned messages will be limited by this value (i.e. their ids will not be lower than `minId`)
    * @param maxId upper bound of returned messages' ids
    * @param limit maximum number of returned messages (limited by 100)
    * @return sequence of messages
    */
  def getMessages(chanId: Int, minId: Int = 0, maxId: Int = 0, limit: Int = 100): Seq[TLAbsMessage] =
    client.messagesGetHistory(chans(chanId), 0, 0, 0, limit min 100, maxId, minId).getMessages.toArray().toSeq
      .collect { case m: TLAbsMessage => m }
}

object TgHandler {
  implicit def toInputPeer(chan: TLChannel): TLInputPeerChannel =
    new TLInputPeerChannel(chan.getId, chan.getAccessHash)

  implicit def toAbsChannel(chan: TLChannel): TLAbsInputChannel =
    new TLInputChannel(chan.getId, chan.getAccessHash)

  implicit def toClient(handler: TgHandler): TelegramClient =
    handler.client
}