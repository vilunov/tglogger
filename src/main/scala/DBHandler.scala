import scalikejdbc._

import com.github.badoualy.telegram.tl.api.TLChannel

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
           username TEXT NULL);""".update().apply()
    }
  }

  def addChannel(chan: TLChannel): Unit = {
    DB autoCommit { implicit session=>
      sql"""INSERT INTO channels VALUES(${chan.getId}, ${chan.getTitle})""".update().apply()
    }
  }
}
