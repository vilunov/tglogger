package tglogger.db

import scalikejdbc._

import tglogger.Vars.DB._

object Schema {
  private val schema = Seq(
    sql"""CREATE TABLE channels (
            id INTEGER NOT NULL,
            title TEXT NOT NULL,
            username TEXT NULL,
            pub BOOLEAN NOT NULL DEFAULT FALSE,
            available BOOLEAN NOT NULL DEFAULT TRUE,
            CONSTRAINT channels_pk PRIMARY KEY (id));""",
    sql"""CREATE TABLE messages (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            msg_time TIMESTAMP NOT NULL,
            msg_body TEXT NULL,
            CONSTRAINT messages_pk PRIMARY KEY (id, channel_id, msg_time),
            CONSTRAINT channel_fk FOREIGN KEY (channel_id) REFERENCES channels(id));""",
    sql"""CREATE TABLE messages_deleted (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            deletion_time TIMESTAMP NULL,
            CONSTRAINT messages_deleted_pk PRIMARY KEY (id, channel_id),
            CONSTRAINT channel_fk FOREIGN KEY (channel_id) REFERENCES channels(id));"""
  )

  def initSchema(): Unit = {
    ConnectionPool.singleton(ConnStr, User, Password)
    DB.localTx { implicit session =>
      schema.foreach(_.update().apply())
    }
  }
}
