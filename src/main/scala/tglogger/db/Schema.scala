package tglogger.db

import scala.concurrent._, duration._

import scalikejdbc._
import scalikejdbc.async._

import tglogger.Vars.DB._

object Schema {
  private val schema = Seq(
    sql"""CREATE TABLE channels (
            id INTEGER NOT NULL,
            title TEXT NOT NULL,
            username TEXT NULL,
            supergroup BOOLEAN NOT NULL,

            pub BOOLEAN NOT NULL DEFAULT FALSE,

            CONSTRAINT channels_pk PRIMARY KEY (id));""",

    sql"""CREATE TABLE messages (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            msg_time TIMESTAMP NOT NULL,

            from_user_id INTEGER NULL,
            reply_msg_id INTEGER NULL,
            via_bot_id INTEGER NULL,

            media_downloaded BOOLEAN NOT NULL DEFAULT FALSE,

            CONSTRAINT messages_pk PRIMARY KEY (id, channel_id),
            CONSTRAINT channel_fk FOREIGN KEY (channel_id) REFERENCES channels(id));""",

    sql"""CREATE TABLE forwards (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            fwd_user_id INTEGER NULL,
            fwd_channel_id INTEGER NULL,
            fwd_message_id INTEGER NULL,

            CONSTRAINT fwd_channel_fields_tied CHECK ((fwd_channel_id IS NULL) = (fwd_message_id IS NULL)),
            CONSTRAINT has_some_value CHECK (NOT (fwd_channel_id IS NULL) OR NOT (fwd_user_id IS NULL)),

            CONSTRAINT forwards_pk PRIMARY KEY (id, channel_id),
            CONSTRAINT messages_fk FOREIGN KEY (id, channel_id) REFERENCES messages(id, channel_id));""",

    sql"""CREATE TABLE message_history (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            msg_time TIMESTAMP NOT NULL,
            msg_body TEXT NOT NULL,

            CONSTRAINT message_history_pk PRIMARY KEY (id, channel_id, msg_time),
            CONSTRAINT messages_fk FOREIGN KEY (id, channel_id) REFERENCES messages(id, channel_id));""",

    sql"""CREATE TABLE messages_deleted (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            deletion_time TIMESTAMP NULL,

            CONSTRAINT messages_deleted_pk PRIMARY KEY (id, channel_id),
            CONSTRAINT channel_fk FOREIGN KEY (channel_id) REFERENCES channels(id),
            CONSTRAINT messages_fk FOREIGN KEY (id, channel_id) REFERENCES messages(id, channel_id));""",

    sql"""CREATE TABLE photos (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            media_id BIGINT NOT NULL,
            CONSTRAINT photos_pk PRIMARY KEY (id, channel_id),
            CONSTRAINT messages_fk FOREIGN KEY (id, channel_id) REFERENCES messages(id, channel_id));""",

    sql"""CREATE TABLE documents (
            id INTEGER NOT NULL,
            channel_id INTEGER NOT NULL,
            media_id BIGINT NOT NULL,
            name TEXT NULL,
            CONSTRAINT documents_pk PRIMARY KEY (id, channel_id),
            CONSTRAINT messages_fk FOREIGN KEY (id, channel_id) REFERENCES messages(id, channel_id));""",

    sql"""CREATE TABLE users (
            id INTEGER NOT NULL,
            username TEXT NULL,
            firstname TEXT NOT NULL,
            lastname TEXT NULL,
            is_bot BOOLEAN NOT NULL,
            CONSTRAINT users_pk PRIMARY KEY (id));"""
  )

  def initSchema(): Unit = {
    AsyncConnectionPool.singleton(ConnStr, User, Password)
    implicit val session: SharedAsyncDBSession = AsyncDB.sharedSession
    for(query <- schema)
      Await.result(query.update().future(), Duration.Inf)
  }
}
