package tglogger.db

import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._
import com.github.badoualy.telegram.tl.api.{TLChannel, TLMessage, TLPeerChannel}
import scalikejdbc.async.ShortenedNames.{EC, ECGlobal}
import tglogger.Vars.DB._

import scala.concurrent._

object DBHandler {
  def connect(): Unit = {
    AsyncConnectionPool.singleton(ConnStr, User, Password)
  }

  def addChannels(chans: Iterator[TLChannel])(implicit cxt: EC = ECGlobal): Future[Vector[Long]] = {
    AsyncDB.localTx { implicit session =>
      Future.sequence(chans.map(addChannel(_)(session)).toVector)
    }
  }

  def addMessages(messages: Iterator[TLMessage])(implicit cxt: EC = ECGlobal): Future[Vector[Long]] = {
    AsyncDB.localTx { implicit session =>
      Future.sequence(messages.map(addMessage(_)(session)).toVector)
    }
  }

  def addChannel(chan: TLChannel)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Long] =
    sql"""INSERT INTO channels(id, title, username)
          VALUES(${chan.getId}, ${chan.getTitle}, ${chan.getUsername})
          ON CONFLICT ON CONSTRAINT channels_pk DO UPDATE SET
          available = true, title = EXCLUDED.title, username = EXCLUDED.username;""".updateAndReturnGeneratedKey()

  def addMessage(msg: TLMessage)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Long] = {
    val msgId = msg.getId
    val chanId = msg.getToId.asInstanceOf[TLPeerChannel].getChannelId
    val date = Option(msg.getEditDate).getOrElse(msg.getDate)
    val text: Option[String] = msg.getMessage match {
      case m: String if !m.isEmpty => Option(m)
      case _ => None
    }
    sql"""INSERT INTO messages(id, channel_id, msg_time, msg_body)
          VALUES($msgId, $chanId, to_timestamp($date), $text)
          ON CONFLICT DO NOTHING;""".updateAndReturnGeneratedKey()
  }

  def getPubChannels(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[List[Int]] =
    sql"SELECT id FROM channels WHERE pub AND available;".map(_.int("id"))
}
