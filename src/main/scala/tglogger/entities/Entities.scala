package tglogger.entities

import scalikejdbc._

case class Channel(id: Int, title: String, username: Option[String])

object Channel extends SQLSyntaxSupport[Channel] {
  override def tableName: String = "channels"

  override def columns: Seq[String] = Seq("id", "title", "username")

  def apply(c: SyntaxProvider[Channel])(rs: WrappedResultSet): Channel = apply(c.resultName)(rs)

  def apply(c: ResultName[Channel])(rs: WrappedResultSet): Channel =
    Channel(rs.int(c.id),rs.string(c.title), rs.stringOpt(c.username))
}

case class HistoryEntry(body: String, timestamp: Int)

case class Message(id: Int, history: Seq[HistoryEntry], timestamp: Int,
                   fromUser: Option[Int], replyTo: Option[Int], viaBot: Option[Int])
