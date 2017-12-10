import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Paths}

import com.github.badoualy.telegram.api.{Kotlogram, TelegramApp, TelegramClient}
import com.github.badoualy.telegram.tl.api._
import com.github.badoualy.telegram.tl.api.auth.TLAuthorization
import com.github.badoualy.telegram.tl.exception.RpcErrorException
import com.github.badoualy.telegram.tl.core.TLIntVector

import Vars.TgClient._

object Main {
  def main(args: Array[String]): Unit = {
    if (args.length == 1 && args(0) == "init") {
      DBHandler.connect()
      DBHandler.initSchema()
      System.exit(0)
    }

    DBHandler.connect()
    loadSession()

    val app = new TelegramApp(ApiId, ApiHash, SessionName, "1", "1", "en")
    implicit val client: TelegramClient = Kotlogram.getDefaultClient(app, Session)
    auth

    val chans = getChannels
    DBHandler.addChannels(chans.iterator)

    chans foreach { chan =>
      val messages = getMessages(chan)
      DBHandler.addMessages(messages.toIterator.collect { case m: TLMessage => m })
    }

    client.close()
  }

  /**
    * Reads the file with session information
    */
  def loadSession(): Unit = {
    if (new File(SessionFilePath).exists()) {
      val s = Files.readAllBytes(Paths.get(SessionFilePath))
      Session.deserialize(s)
    }
  }

  /**
    * Saves the session information into the file
    */
  def saveSession(): Unit = {
    val fos = new FileOutputStream(SessionFilePath)
    try {
      fos.write(Session.serialize().toArray)
    } finally {
      fos.close()
    }
  }

  /**
    * If needed, ask user for login inputs and authenticate them
    *
    * @param client client instance
    */
  def auth(implicit client: TelegramClient): Unit = {
    import scala.io.StdIn.readLine

    if (Session.key.isDefined) return

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
    saveSession()
  }

  /**
    * Get all available channels
    *
    * @param client logged in client
    * @return sequence of all subscribed channels
    */
  def getChannels(implicit client: TelegramClient): Seq[TLChannel] =
    client.messagesGetAllChats(new TLIntVector).getChats.toArray().toSeq
      .collect { case m: TLChannel if !m.getMegagroup => m }

  /**
    * Get messages from a channel
    *
    * @param chan channel to query messages from
    * @param minId the returned messages will be limited by this value (i.e. their ids will not be lower than `minId`)
    * @param maxId upper bound of returned messages' ids
    * @param limit maximum number of returned messages (limited by 100)
    * @param client logged in client
    * @return sequence of messages
    */
  def getMessages(chan: TLInputPeerChannel, minId: Int = 0, maxId: Int = 100, limit: Int = 100)
                 (implicit client: TelegramClient): Seq[TLAbsMessage] =
    client.messagesGetHistory(chan, 0, 0, 0, limit min 100, maxId, minId).getMessages.toArray().toSeq
      .collect { case m: TLAbsMessage => m }

  private implicit def toInputPeer(chan: TLChannel): TLInputPeerChannel =
    new TLInputPeerChannel(chan.getId, chan.getAccessHash)

  private implicit def toAbsChannel(chan: TLChannel): TLAbsInputChannel =
    new TLInputChannel(chan.getId, chan.getAccessHash)
}
