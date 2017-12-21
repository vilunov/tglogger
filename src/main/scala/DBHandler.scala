import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._
import com.github.badoualy.telegram.tl.api.{TLChannel, TLMessage, TLPeerChannel}
import Vars.DB._

import scala.concurrent.Future

object DBHandler {
  def connect(): Unit = {
    AsyncConnectionPool.singleton(ConnStr, User, Password)
  }

  def initSchema(): Future[Unit] = {
    AsyncDB.localTx { implicit session =>
      sql"""CREATE TABLE channels (
            id INTEGER NOT NULL,
            title TEXT NOT NULL,
            username TEXT NULL,
            pub BOOLEAN NOT NULL DEFAULT false,
            available BOOLEAN NOT NULL DEFAULT true,
            CONSTRAINT channels_pk PRIMARY KEY (id));""".update().apply()

      sql"""CREATE TABLE messages (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            msg_time TIMESTAMP NOT NULL,
            msg_body TEXT NULL,
            CONSTRAINT messages_pk PRIMARY KEY (id, channel_id, msg_time));""".update().apply()

      sql"""CREATE TABLE messages_deleted (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            deletion_time TIMESTAMP NULL,
            CONSTRAINT messages_deleted_pk PRIMARY KEY (id, channel_id));""".update().apply()

      Future.unit
    }
  }

  def addChannels(chans: Iterator[TLChannel]): Unit = {
    DB autoCommit { implicit session =>
      sql"UPDATE channels SET available = false;".update().apply()
      for (chan <- chans)
        sql"""INSERT INTO channels(id, title, username)
              VALUES(${chan.getId}, ${chan.getTitle}, ${chan.getUsername})
              ON CONFLICT ON CONSTRAINT channels_pk DO UPDATE SET
              available = true, title = EXCLUDED.title, username = EXCLUDED.username;""".update().apply()
    }
  }

  def addMessages(messages: Iterator[TLMessage]): Unit = {
    DB autoCommit { implicit session =>
      for (msg <- messages) {
        val msgId = msg.getId
        val chanId = msg.getToId.asInstanceOf[TLPeerChannel].getChannelId
        val date = Option(msg.getEditDate).getOrElse(msg.getDate)
        var text = msg.getMessage
        if (text.isEmpty) text = null

        sql"""INSERT INTO messages(id, channel_id, msg_time, msg_body)
              VALUES($msgId, $chanId, to_timestamp($date), $text)
              ON CONFLICT DO NOTHING;""".update().apply()
      }
    }
  }

  def getPubChannels: Seq[Int] = {
    DB readOnly { implicit session =>
      sql"SELECT id FROM channels WHERE pub AND available;".map(_.int("id")).list().apply()
    }
  }
}
