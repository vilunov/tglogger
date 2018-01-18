package tglogger.entities

case class Channel(id: Int, title: String, username: Option[String])

case class HistoryEntry(body: String, timestamp: Int)

case class Message(id: Int, history: Seq[HistoryEntry], timestamp: Int,
                   fromUser: Option[Int], replyTo: Option[Int], viaBot: Option[Int])
