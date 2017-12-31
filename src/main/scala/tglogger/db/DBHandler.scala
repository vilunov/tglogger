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

  def addMessages(messages: Iterator[TLAbsMessage])
                 (implicit cxt: ExecutionContext): Future[Unit] = {
    AsyncDB.withPool { implicit session =>
      Future.sequence(messages.map(addMessage)).map(_ => ())
    }
  }

  def addChannel(chan: TLChannel)
                (implicit session: AsyncDBSession = AsyncDB.sharedSession, cxt: ExecutionContext): Future[Unit] =
    sql"""INSERT INTO channels(id, title, username)
          VALUES(${chan.getId}, ${chan.getTitle}, ${chan.getUsername})
          ON CONFLICT ON CONSTRAINT channels_pk DO UPDATE SET
          available = true, title = EXCLUDED.title, username = EXCLUDED.username;""".update().future().map(_ => ())

  def addMessage(msg: TLAbsMessage)
                (implicit session: AsyncDBSession = AsyncDB.sharedSession, cxt: ExecutionContext): Future[Unit] = {
    msg match {
      case msg: TLMessage =>
        val msgId = msg.getId
        val chanId = msg.getToId.asInstanceOf[TLPeerChannel].getChannelId
        val date = Option(msg.getEditDate).getOrElse(msg.getDate)
        val text: Option[String] = msg.getMessage match {
          case m: String if !m.isEmpty => Option(m)
          case _ => None
        }
        sql"""INSERT INTO messages(id, channel_id, msg_time, msg_body)
          VALUES($msgId, $chanId, to_timestamp($date), $text)
          ON CONFLICT DO NOTHING;""".update().future().map(_ => ())
      case _ => Future.unit
    }
  }

  def getPubChannels(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[List[Int]] = {
    sql"SELECT id FROM channels WHERE pub AND available;".map(_.int("id"))
  }
}
