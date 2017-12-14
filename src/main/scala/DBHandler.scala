import scalikejdbc._
import com.github.badoualy.telegram.tl.api.{TLChannel, TLMessage, TLPeerChannel}

import Vars.DB._

object DBHandler {
  def connect(): Unit = {
    val settings = ConnectionPoolSettings(initialSize = 1, maxSize = 3, connectionTimeoutMillis = 3000L)
    ConnectionPool.singleton(ConnStr, User, Password, settings)
  }

  def initSchema(): Unit = {
    DB autoCommit { implicit session =>
      sql"""CREATE TABLE channels (
            id INTEGER PRIMARY KEY,
            title TEXT NOT NULL,
            username TEXT NULL,
            pub BOOLEAN NOT NULL DEFAULT false);""".update().apply()

      sql"""CREATE TABLE messages (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            msg_time TIMESTAMP NOT NULL,
            msg_body TEXT NULL,
            CONSTRAINT messages_pkey PRIMARY KEY (id, channel_id, msg_time));""".update().apply()

      sql"""CREATE TABLE deleted_messages (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            CONSTRAINT deleted_messages_pkey PRIMARY KEY (id, channel_id));""".update().apply()
    }
  }

  def addChannels(chans: Iterator[TLChannel]): Unit = {
    DB autoCommit { implicit session =>
      for (chan <- chans)
        sql"""INSERT INTO channels(id, title, username)
              VALUES(${chan.getId}, ${chan.getTitle}, ${chan.getUsername})
              ON CONFLICT DO NOTHING;""".update().apply()
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

  def getPubChannels: List[Int] = {
    DB readOnly { implicit session =>
      sql"SELECT id FROM channels WHERE pub;".map(_.int("id")).list().apply()
    }
  }
}
