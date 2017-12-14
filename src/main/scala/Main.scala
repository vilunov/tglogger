object Main {
  def main(args: Array[String]): Unit = {
    DBHandler.connect()
    if (args.length == 1) {
      args(0) match {
        case "init" =>
          DBHandler.initSchema()
          System.exit(0)
        case "chans" =>
          DBHandler.getPubChannels.foreach(println(_))
      }
      System.exit(0)
    }

    implicit val session: TgSession = new TgSession
    SessionLoader.loadSession()
    implicit val handler: TgHandler = new TgHandler(session)
    handler.updateChannels()

    handler.close()
  }
}
