package tglogger.db

import scala.concurrent._

import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._

import com.github.badoualy.telegram.tl.api._

import tglogger.Vars.DB._

object DBHandler {
  def connect(): Unit = {
    AsyncConnectionPool.singleton(ConnStr, User, Password, AsyncConnectionPoolSettings(maxPoolSize = 8, maxQueueSize = 1024))
  }

  def addChannels(chans: Iterator[TLChannel])
                 (implicit cxt: ExecutionContext): Future[Unit] = {
    AsyncDB.withPool { implicit session =>
      Future.sequence(chans.map(addChannel)).map(_ => ())
    }
  }

  def addMessages(messages: Iterator[TLMessage])
                 (implicit cxt: ExecutionContext): Future[Unit] = {
    AsyncDB.withPool { implicit session =>
      Future.sequence(messages.map(addMessage)).map(_ => ())
    }
  }

  def removeMessages(chanId: Int, msgIds: Iterator[Int])
                   (implicit cxt: ExecutionContext): Future[Unit] = {
    import java.time.Instant

    val timestamp: Long = Instant.now.getEpochSecond
    AsyncDB.withPool { implicit session =>
      Future.sequence(msgIds.map(msgId =>
        sql"""INSERT INTO messages_deleted(id, channel_id, deletion_time)
              VALUES($msgId, $chanId, to_timestamp($timestamp))
              ON CONFLICT DO NOTHING;""".update().future().map(_ => ())
      )).map(_ => ())
    }
  }

  def addChannel(chan: TLChannel)
                (implicit session: AsyncDBSession = AsyncDB.sharedSession, cxt: ExecutionContext): Future[Unit] =
    sql"""INSERT INTO channels(id, title, username)
          VALUES(${chan.getId}, ${chan.getTitle}, ${chan.getUsername})
          ON CONFLICT ON CONSTRAINT channels_pk DO UPDATE SET
          available = true, title = EXCLUDED.title, username = EXCLUDED.username;""".update().future().map(_ => ())

  /**
    * Inserts message data into the database.
    *
    * @param msg Telegram message
    * @param session
    * @param cxt
    * @return
    */
  def addMessage(msg: TLMessage)
                (implicit session: AsyncDBSession = AsyncDB.sharedSession, cxt: ExecutionContext): Future[Unit] = {
    if (!msg.getToId.isInstanceOf[TLPeerChannel]) return Future.unit

    val msgId = msg.getId
    val chanId = msg.getToId.asInstanceOf[TLPeerChannel].getChannelId
    val date = Option(msg.getEditDate).getOrElse(msg.getDate)
    val mediaDownloaded = !{
      val media = msg.getMedia
      media.isInstanceOf[TLMessageMediaPhoto] || media.isInstanceOf[TLMessageMediaDocument]
    }

    val fromUserId = Option(msg.getFromId)
    val replyMsgId = Option(msg.getReplyToMsgId)
    val viaBotId = Option(msg.getViaBotId)

    // 1. Insert the message metadata
    sql"""INSERT INTO messages(id, channel_id, msg_time, media_downloaded,
            from_user_id, reply_msg_id, via_bot_id)
          VALUES($msgId, $chanId, to_timestamp($date), $mediaDownloaded,
            $fromUserId, $replyMsgId, $viaBotId)
          ON CONFLICT DO NOTHING;""".update().future().flatMap { _ =>
      // 2. Insert message text (or media caption) into the history relation
      val text = msg.getMedia match {
        case m: TLMessageMediaPhoto => m.getCaption
        case m: TLMessageMediaDocument => m.getCaption
        case _ => msg.getMessage
      }
      val time = Option(msg.getEditDate).getOrElse(msg.getDate)

      val futureMsg = if (text != null && !text.isEmpty) {
        sql"""INSERT INTO message_history(id, channel_id, msg_time, msg_body)
              VALUES($msgId, $chanId, to_timestamp($time), $text)
              ON CONFLICT DO NOTHING;""".update().future().map(_ => ())
      } else Future.unit

      // 3. Insert forward data (if the message was a forward)
      val futureFwd = msg.getFwdFrom match {
        case fwdHeader: TLMessageFwdHeader =>
          val fwdUserId = fwdHeader.getFromId
          val fwdChanId = fwdHeader.getChannelId
          val fwdMsgId = fwdHeader.getChannelPost
          sql"""INSERT INTO forwards(id, channel_id, fwd_user_id, fwd_channel_id, fwd_message_id)
                VALUES($msgId, $chanId, $fwdUserId, $fwdChanId, $fwdMsgId)
                ON CONFLICT DO NOTHING;""".update().future()
        case _ => Future.unit
      }

      val futureMedia = msg.getMedia match {
        case m: TLMessageMediaDocument =>
          val doc = m.getDocument.getAsDocument
          if (doc != null) {
            val name = doc.getAttributes.toArray().collectFirst { case at: TLDocumentAttributeFilename => at }.map(_.getFileName)
            sql"INSERT INTO documents(id, channel_id, media_id, name) VALUES($msgId, $chanId, ${doc.getId}, $name) ON CONFLICT DO NOTHING;".update().future()
          } else Future.unit
        case m: TLMessageMediaPhoto =>
          val photo = m.getPhoto.getAsPhoto
          if (photo != null)
            sql"INSERT INTO photos(id, channel_id, media_id) VALUES($msgId, $chanId, ${photo.getId}) ON CONFLICT DO NOTHING;".update().future()
          else Future.unit
      }

      Future.sequence(Seq(futureMsg, futureFwd, futureMedia)).map(_ => ())
    }
  }

  def getPubChannels(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[List[Int]] =
    sql"SELECT id FROM channels WHERE pub AND available;".map(_.int("id"))

  def isMediaDownloaded(msgId: Int, chanId: Int)
                       (implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Option[Boolean]] =
    sql"SELECT media_downloaded FROM messages WHERE id = $msgId AND channel_id = $chanId;"
      .map(_.boolean("media_downloaded"))

  def setMediaDownloaded(msgId: Int, chanId: Int)
                        (implicit session: AsyncDBSession = AsyncDB.sharedSession, cxt: ExecutionContext): Future[Unit] =
    sql"UPDATE messages SET media_downloaded = true WHERE id = $msgId AND channel_id = $chanId;".update().future().map(_ => ())
}
