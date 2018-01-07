package tglogger

import scala.util.Success
import scala.concurrent._
import scala.collection.mutable
import scala.annotation.tailrec

import akka.actor.{Actor, ActorRef, Props}

import com.github.badoualy.telegram.api._
import com.github.badoualy.telegram.tl.api._, auth.TLAuthorization
import com.github.badoualy.telegram.tl.exception.RpcErrorException
import com.github.badoualy.telegram.tl.core.TLIntVector

import tglogger.Vars.TgClient._
import tglogger.db.DBHandler
import tglogger.TgHandler._

/**
  * Actor responsible for communicating with Telegram servers.
  * Maintains one account connection.
  *
  * @param session session parameters
  */
class TgHandler(val session: TgSession = new TgSession()) extends Actor with UpdateCallback {
  val app = new TelegramApp(ApiId, ApiHash, SessionName, "1", "1", "en")
  val client: TelegramClient = Kotlogram.getDefaultClient(app, session, Kotlogram.PROD_DC4, this)

  val mediaDownloader: Option[ActorRef] =
    if (DownloadMedia) Some(context.actorOf(Props(new TgMediaDownloader(client.getDownloaderClient))))
    else None
  val chans: mutable.HashMap[Int, TLChannel] = mutable.HashMap()
  var lastRequest: Long = 0
  private implicit def dispatcher: ExecutionContextExecutor = context.dispatcher

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
    chans ++= channels.map { chan => chan.getId -> chan }
  }

  def getChannels: Iterator[TLChannel] =
    chans.valuesIterator

  /**
    * Get messages from a channel
    *
    * @param chanId channel to query messages from
    * @param minId the returned messages will be limited by this value (i.e. their ids will not be lower than `minId`)
    * @param maxId upper bound of returned messages' ids
    * @param limit maximum number of returned messages (limited by 100)
    * @return sequence of messages
    */
  @tailrec final def getMessages(chanId: Int, minId: Int = 0, maxId: Int = 0, limit: Int = 100, offset: Int = 0, tries: Int = 3): Seq[TLAbsMessage] = {
    try client.messagesGetHistory(chans(chanId), 0, 0, offset, limit min 100, maxId, minId).getMessages
      .toArray.collect { case m: TLAbsMessage => m }
    catch {
      case e: RpcErrorException if tries <= 0 => throw e
      case e: RpcErrorException =>
        println(s"Thrown RpcErrorException in getMessages\n" +
          s"(chanId = $chanId, minId = $minId, maxId = $maxId, limit = $limit, offset = $offset, tries = $tries)")
        e.printStackTrace()
        getMessages(chanId, minId, maxId, limit, offset, tries - 1);
    }
  }

  /**
    * We need to do this because Telegram limits the number of requests.
    * This method blocks the actor for some fixed time. This interval was chosen arbitrarily.
    *
    * It was tested that an interval of half a second eventually leads to temporary client ban,
    * resulting in FLOOD_WAIT RPC exceptions.
    *
    * TODO: find out actual Telegram limits via trial and error
    */
  private def waitCooldown(): Unit = {
    val cooldown: Long = 1000

    val currentTime = System.currentTimeMillis()
    if (currentTime - lastRequest < cooldown) Thread.sleep(cooldown - (currentTime - lastRequest))
    lastRequest = currentTime
  }

  override def receive: Receive = {
    case MsgTgClose =>
      client.close()
    case MsgTgUpdateChannels =>
      waitCooldown()
      updateChannels()
      DBHandler.addChannels(chans.values.iterator)(context.dispatcher)
    case MsgTgGetAllMessages =>
      waitCooldown()
      DBHandler.getPubChannels.onComplete {
        case Success(list) =>
          list.foreach(self ! MsgTgGetMessages(_, 0))
        case _ =>
      }
    case MsgTgGetMessages(chanId, offset) =>
      if(chans.contains(chanId)) {
        waitCooldown()
        val msgs = getMessages(chanId, offset = offset).collect { case m: TLMessage => m }
         mediaDownloader.foreach(a => msgs.foreach(m => a ! (m.getMedia, chanId, m.getId)))
        DBHandler.addMessages(msgs.iterator)(context.dispatcher)
        if (msgs.nonEmpty)
          self ! MsgTgGetMessages(chanId, offset + msgs.length)
      }
  }

  override def onUpdates(client: TelegramClient, updates: TLUpdates): Unit =
    updates.getUpdates.forEach(handleUpdate(_))

  override def onUpdatesCombined(client: TelegramClient, updates: TLUpdatesCombined): Unit =
    updates.getUpdates.forEach(handleUpdate(_))

  override def onUpdateShort(client: TelegramClient, update: TLUpdateShort): Unit =
    handleUpdate(update.getUpdate)

  override def onShortChatMessage(client: TelegramClient, message: TLUpdateShortChatMessage): Unit = {
    println(message)
    println(message.getMessage)
  }

  override def onShortMessage(client: TelegramClient, message: TLUpdateShortMessage): Unit = {
    println(message)
    println(message.getMessage)
  }

  override def onShortSentMessage(client: TelegramClient, message: TLUpdateShortSentMessage): Unit = {
    println(message)
  }

  override def onUpdateTooLong(client: TelegramClient): Unit = {}

  def handleUpdate(update: TLAbsUpdate): Unit = update match {
    case m: TLUpdateNewChannelMessage =>
      m.getMessage match {
        case msg: TLMessage =>
          msg.getToId match {
            case chan: TLPeerChannel if chans.contains(chan.getChannelId) =>
              mediaDownloader.foreach(_ ! (msg.getMedia, chan.getChannelId, msg.getId))
              DBHandler.addMessage(msg)
            case _ =>
          }
        case _ =>
      }
    case m: TLUpdateEditMessage =>
      m.getMessage match {
        case msg: TLMessage =>
          msg.getToId match {
            case chan: TLPeerChannel if chans.contains(chan.getChannelId) =>
              mediaDownloader.foreach(_ ! (msg.getMedia, chan.getChannelId, msg.getId))
              DBHandler.addMessage(msg)
            case _ =>
          }
        case _ =>
      }
    case m: TLUpdateDeleteChannelMessages =>
      DBHandler.removeMessages(m.getChannelId, m.getMessages.toIntArray.filter(chans.contains).iterator)
    case _ =>
  }
}

object TgHandler {
  implicit def toInputPeer(chan: TLChannel): TLInputPeerChannel =
    new TLInputPeerChannel(chan.getId, chan.getAccessHash)

  implicit def toAbsChannel(chan: TLChannel): TLInputChannel =
    new TLInputChannel(chan.getId, chan.getAccessHash)
}

/**
  * Close the connection and all Telegram client's threads.
  */
case object MsgTgClose

/**
  * Update the channel list and insert it into the DB.
  */
case object MsgTgUpdateChannels

/**
  * Retrieve all channels' messages starting from the server and insert them into the DB.
  */
case object MsgTgGetAllMessages

/**
  * Retrieve up to 100 channel's messages from the server and insert them into the DB.
  *
  * @param chanId channel's id
  * @param offset the offset from the last message (must be >= 0)
  */
case class MsgTgGetMessages(chanId: Int, offset: Int) {
  require(offset >= 0, "offset has to be bigger or equal to 0")
}