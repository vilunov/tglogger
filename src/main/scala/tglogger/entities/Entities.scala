package tglogger.entities

case class Channel(id: Int, title: String, username: Option[String])

case class Message(id: Int, message: String, timestamp: Int)
