package tglogger

import scala.concurrent._, duration._
import scala.collection.JavaConverters._
import scala.collection.mutable

import akka.actor.{Actor, ActorRef, Props, Timers}

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
class TgHandler(private val session: TgSession = new TgSession()) extends Actor with Timers with UpdateCallback {
  private val app = new TelegramApp(ApiId, ApiHash, SessionName, "1", "1", "en")
  private val client: TelegramClient = Kotlogram.getDefaultClient(app, session, Kotlogram.PROD_DC4, this)

  private val mediaDownloader: Option[ActorRef] =
    if (DownloadMedia) Some(context.actorOf(Props(new TgMediaDownloader(client.getDownloaderClient))))
    else None
  private var chans: Map[Int, TLChannel] = Map.empty
  private val tasks: mutable.Queue[TgTask] = mutable.Queue()
  private var readyToSend: Boolean = true

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
      case e: RpcErrorException if e.getTag == "SESSION_PASSWORD_NEEDED" =>
        // Login failed due to two-step authorization
        // Ask user for password and try again
        print("Insert password: ")
        val pwd = readLine()
        client.authCheckPassword(pwd)
    }

    val self = auth.getUser.getAsUser
    println(s"You are signed in as ${self.getFirstName} ${self.getLastName}")
  }

  def getChannels: Iterator[TLChannel] =
    chans.valuesIterator

  override def receive: Receive = {
    case task: TgTask =>
      if(tasks.isEmpty && readyToSend) handleTask(task)
      else tasks.enqueue(task)
    case MsgTgClose =>
      client.close()
      mediaDownloader.foreach(_ ! MsgTgClose)
      context.stop(self)
    case MsgTgGetAllMessages =>
      tasks.enqueue(chans.keysIterator.map(MsgTgGetMessages(_, 0)).toSeq: _*)
    case MsgTgReadyToRun =>
      if(tasks.nonEmpty) handleTask(tasks.dequeue())
      else readyToSend = true
  }

  private def handleTask(task: TgTask): Unit = {
    readyToSend = false
    task match {
      case MsgTgUpdateChannels =>
        // Retrieve the list of channels, add them to the local map and insert into the DB
        val channels = client.messagesGetAllChats(new TLIntVector).getChats.asScala.collect { case m: TLChannel => m }
        timers.startSingleTimer(CooldownTimerKey, MsgTgReadyToRun, 1 second)
        chans = Map(channels.map { chan => chan.getId -> chan }: _*)
        DBHandler.addChannels(chans.values.iterator)
      case MsgTgGetMessages(chanId, offset) =>
        if(chans.contains(chanId)) {
          val response = client.messagesGetHistory(chans(chanId), offset, 0, 0, 100, 0, 0)
          timers.startSingleTimer(CooldownTimerKey, MsgTgReadyToRun, 1 second)
          val msgs: Seq[TLMessage] = response.getMessages.asScala.collect { case m: TLMessage => m }
          val usrs: Seq[TLAbsUser] = response.getUsers.asScala
          mediaDownloader.foreach { a => msgs.filter(_.getMedia != null).foreach(a ! _.getMedia) }
          DBHandler.addMessages(msgs.iterator)
          DBHandler.addUsers(usrs.iterator)
          if (msgs.nonEmpty) tasks.enqueue(MsgTgGetMessages(chanId, msgs.minBy(_.getId).getId))
        } else self ! MsgTgReadyToRun
    }
  }

  override def onUpdates(client: TelegramClient, updates: TLUpdates): Unit =
    updates.getUpdates.forEach(handleUpdate(_))

  override def onUpdatesCombined(client: TelegramClient, updates: TLUpdatesCombined): Unit =
    updates.getUpdates.forEach(handleUpdate(_))

  override def onUpdateShort(client: TelegramClient, update: TLUpdateShort): Unit =
    handleUpdate(update.getUpdate)

  override def onShortChatMessage(client: TelegramClient, message: TLUpdateShortChatMessage): Unit = {}

  override def onShortMessage(client: TelegramClient, message: TLUpdateShortMessage): Unit = {}

  override def onShortSentMessage(client: TelegramClient, message: TLUpdateShortSentMessage): Unit = {}

  override def onUpdateTooLong(client: TelegramClient): Unit = {}

  private def handleUpdate(update: TLAbsUpdate): Unit = update match {
    /*
     * New message in a channel or a supergroup
     */
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
    /*
     * A message has been edited (either in a channel, supergroup, group or a personal chat)
     */
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
    /*
     * A message has been deleted from a channel or a supergroup
     */
    case m: TLUpdateDeleteChannelMessages =>
      if(chans.contains(m.getChannelId))
        DBHandler.removeMessages(m.getChannelId, m.getMessages.toIntArray.iterator)
    case _ =>
  }
}

object TgHandler {
  implicit def toInputPeer(chan: TLChannel): TLInputPeerChannel =
    new TLInputPeerChannel(chan.getId, chan.getAccessHash)

  implicit def toAbsChannel(chan: TLChannel): TLInputChannel =
    new TLInputChannel(chan.getId, chan.getAccessHash)

  private case object CooldownTimerKey
  private case object MsgTgReadyToRun
}

/**
  * Tasks to be queued inside the actor which require sending requests to Telegram servers.
  */
sealed trait TgTask

/**
  * Close the connection and all Telegram client's threads.
  */
case object MsgTgClose

/**
  * Update the channel list and insert it into the DB.
  */
case object MsgTgUpdateChannels extends TgTask

/**
  * Retrieve all channels' messages starting from the server and insert them into the DB.
  */
case object MsgTgGetAllMessages

/**
  * Retrieve up to 100 channel's messages from the server and insert them into the DB.
  *
  * @param chanId channel's id
  * @param offset the id offset, acts as an upper limit for the returned messages
  */
case class MsgTgGetMessages(chanId: Int, offset: Int) extends TgTask {
  require(offset >= 0, "offset has to be bigger or equal to 0")
}