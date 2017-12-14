import com.github.badoualy.telegram.tl.api.TLMessage

object TgPoller {
  def updateMessages(chanId: Int)(implicit handler: TgHandler): Unit = {
    var messages = handler.getMessages(chanId)
    if (messages.isEmpty) return
    var id = messages.minBy(_.getId).getId
    DBHandler.addMessages(messages.toIterator.collect { case m: TLMessage => m })

    while (messages.nonEmpty && id > 1) {
      println(id)
      Thread.sleep(100)

      messages = handler.getMessages(chanId, maxId = id - 1)
      id = messages.minBy(_.getId).getId
      DBHandler.addMessages(messages.toIterator.collect { case m: TLMessage => m })
    }
  }
}
