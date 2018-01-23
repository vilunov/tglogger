package tglogger.db

import scala.concurrent.{ExecutionContext, Future}

import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._

import com.github.badoualy.telegram.tl.api._

import tglogger.Vars.DB._
import tglogger.entities._

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

  def addUsers(users: Iterator[TLAbsUser])
              (implicit cxt: ExecutionContext): Future[Unit] = {
    AsyncDB.withPool { implicit session =>
      Future.sequence(users.map(addUser)).map(_ => ())
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

  /*
   * Singular inserts
   */

  def addChannel(chan: TLChannel)
                (implicit session: AsyncDBSession = AsyncDB.sharedSession, cxt: ExecutionContext): Future[Unit] =
    sql"""INSERT INTO channels(id, title, username, supergroup)
          VALUES(${chan.getId}, ${chan.getTitle}, ${chan.getUsername}, ${chan.getMegagroup})
          ON CONFLICT ON CONSTRAINT channels_pk DO UPDATE SET
          title = EXCLUDED.title, username = EXCLUDED.username;""".update().future().map(_ => ())

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

  def addUser(user : TLAbsUser)
                (implicit session: AsyncDBSession = AsyncDB.sharedSession, cxt: ExecutionContext): Future[Unit] =  user match {
    case u: TLUser =>
      sql"""INSERT INTO users(id, username, firstname, lastname, is_bot)
            VALUES (${u.getId}, ${u.getUsername}, ${u.getFirstName}, ${u.getLastName}, ${u.getBot})
            ON CONFLICT ON CONSTRAINT users_pk DO UPDATE SET
            username = EXCLUDED.username, firstname = EXCLUDED.firstname, lastname = EXCLUDED.lastname;""".update().future().map(_ => ())
    case _ => Future.unit
  }

  /*
   * Getters and setters
   */

  def getPubChannelsIds(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Seq[Int]] =
    sql"SELECT id FROM channels WHERE pub;".map(_.int(1))

  def getPubChannels(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Seq[Channel]] =
    sql"SELECT id, title, username FROM channels WHERE pub ORDER BY id;"
      .map { it => Channel(it.int(1), it.string(2), it.stringOpt(3)) }

  def getMessages(channel: Int, fromId: Int = 1)
                 (implicit session: AsyncDBSession = AsyncDB.sharedSession, cxt: ExecutionContext): Future[List[Message]] = {
    val history: Future[List[(Int, Int, String)]] =
      sql"SELECT id, msg_time, msg_body FROM message_history WHERE channel_id = $channel AND id >= $fromId ORDER BY id, msg_time;"
        .map { it => (it.int(1), it.timestamp(2).toInstant.getEpochSecond.intValue(), it.string(3)) }.toList()
    val messages: Future[List[(Int, Int, Option[Int], Option[Int], Option[Int])]] =
      sql"SELECT id, msg_time, from_user_id, reply_msg_id, via_bot_id FROM messages WHERE channel_id = $channel AND id >= $fromId ORDER BY id, msg_time;"
      .map { it => (it.int(1), it.timestamp(2).toInstant.getEpochSecond.intValue(), it.intOpt(3), it.intOpt(4), it.intOpt(5)) }.toList()
    val history_grouped: Future[Map[Int, List[HistoryEntry]]] = history.map { it => it.groupBy(_._1).mapValues(_.map { it2 => HistoryEntry(it2._3, it2._2) }) }

    for {
      his <- history_grouped
      msgs <- messages
    } yield msgs.map { it => Message(it._1, if(his.contains(it._1)) his(it._1) else Seq(), it._2, it._3, it._4, it._5) }
  }

  def isPubChannel(chanId: Int)
                  (implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Option[Boolean]] =
    sql"SELECT pub FROM channels WHERE id = $chanId;".map(_.boolean(1))

  def isMediaDownloaded(msgId: Int, chanId: Int)
                       (implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Option[Boolean]] =
    sql"SELECT media_downloaded FROM messages WHERE id = $msgId AND channel_id = $chanId;".map(_.boolean(1))

  def setMediaDownloaded(msgId: Int, chanId: Int)
                        (implicit session: AsyncDBSession = AsyncDB.sharedSession, cxt: ExecutionContext): Future[Unit] =
    sql"UPDATE messages SET media_downloaded = true WHERE id = $msgId AND channel_id = $chanId;".update().future().map(_ => ())
}
