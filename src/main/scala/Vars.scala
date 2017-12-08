object Vars {
  object TgClient {
    val ApiId = 0
    val ApiHash = ""
    val SessionName = "tglogger"
    val SessionFilePath = "session"
  }

  object DB {
    val DBName = "tglogger"
    val User = "tglogger"
    val Password = ""
    val Host = "localhost"
    val ConnStr = s"jdbc:postgresql://$Host/$DBName?user=$User"
  }
}
