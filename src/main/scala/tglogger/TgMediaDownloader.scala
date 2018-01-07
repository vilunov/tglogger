package tglogger

import scala.util.Success
import scala.concurrent.ExecutionContextExecutor
import java.io.FileOutputStream
import java.nio.file.{Path, Paths}

import akka.actor.Actor
import com.github.badoualy.telegram.api.TelegramClient
import com.github.badoualy.telegram.api.utils.TLMediaUtilsKt
import com.github.badoualy.telegram.tl.api._

import tglogger.db.DBHandler

class TgMediaDownloader(val client: TelegramClient, val dir: Path = Paths.get(".")) extends Actor {
  var lastRequest: Long = 0
  dir.toFile.mkdirs()
  dir.resolve("photos").toFile.mkdir()
  dir.resolve("files").toFile.mkdir()

  private def waitCooldown(): Unit = {
    val cooldown: Long = 1000

    val currentTime = System.currentTimeMillis()
    if (currentTime - lastRequest < cooldown) Thread.sleep(cooldown - (currentTime - lastRequest))
    lastRequest = currentTime
  }


  override def receive: Receive = {
    /*
     * Case for downloading photos. Downloads into file "photos/<chan-id>/<msg-id>"
     */
    case (media: TLMessageMediaPhoto, chanId: Int, msgId: Int) =>
      implicit val ec: ExecutionContextExecutor = context.dispatcher
      val file = dir.resolve(Paths.get("photos", chanId.toString, msgId.toString)).toFile
      DBHandler.isMediaDownloaded(msgId, chanId).onComplete {
        case Success(Some(false)) =>
          val mediaInput = TLMediaUtilsKt.getAbsMediaInput(media)
          file.getParentFile.mkdir()
          if(file.exists()) file.delete()
          val stream = new FileOutputStream(file)
          client.downloadSync(mediaInput.getInputFileLocation, mediaInput.getSize, stream)
          DBHandler.setMediaDownloaded(msgId, chanId)
          stream.close()
        case _ =>
      }

    /*
     * Case for downloading files.
     * If the file has a name, downloads into file "files/<chan-id>/<msg-id>-<filename>",
     * If the file has no name (e.g. an attached video), downloads into file "files/<chan-id>/<msg-id>"
     */
    case (media: TLMessageMediaDocument, chanId: Int, msgId: Int) =>
      implicit val ec: ExecutionContextExecutor = context.dispatcher
      media.getDocument match {
        case document: TLDocument =>
          val filename = msgId.toString +
            document.getAttributes.toArray().collectFirst { case at: TLDocumentAttributeFilename => at }
              .map("-" + _.getFileName).getOrElse("")
          val file = dir.resolve(Paths.get("files", chanId.toString, filename)).toFile
          DBHandler.isMediaDownloaded(msgId, chanId).onComplete {
            case Success(Some(false)) =>
              val mediaInput = TLMediaUtilsKt.getAbsMediaInput(media)
              file.getParentFile.mkdir()
              if(file.exists()) file.delete()
              val stream = new FileOutputStream(file)
              client.downloadSync(mediaInput.getInputFileLocation, mediaInput.getSize, stream)
              DBHandler.setMediaDownloaded(msgId, chanId)
              stream.close()
            case _ =>
          }
      }
  }
}