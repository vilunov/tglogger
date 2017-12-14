object Main {
  def main(args: Array[String]): Unit = {
    if (args.length == 1) {
      args(0) match {
        case "init" => init()
        case "list-chans" => list_chans()
      }
    } else default()
  }

  def default(): Unit = {
    DBHandler.connect()

    implicit val session: TgSession = new TgSession
    SessionLoader.loadSession()
    implicit val tg: TgHandler = new TgHandler(session)
    tg.updateChannels()

    DBHandler.addChannels(tg.getChannels)

    val pubChans = DBHandler.getPubChannels
    pubChans foreach { chan =>
      TgPoller.updateMessages(chan)
    }

    tg.close()
  }

  def init(): Unit = {
    DBHandler.connect()
    DBHandler.initSchema()
  }

  def list_chans(): Unit = {
    DBHandler.connect()
    DBHandler.getPubChannels.foreach(println(_))
  }
}
