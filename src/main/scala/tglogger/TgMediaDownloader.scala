package tglogger

import scala.concurrent.ExecutionContextExecutor
import java.io.{File, FileOutputStream, IOException}
import java.nio.file.{Path, Paths}

import akka.actor.Actor
import com.github.badoualy.telegram.api.TelegramClient
import com.github.badoualy.telegram.api.utils.{MediaInput, TLMediaUtilsKt}
import com.github.badoualy.telegram.tl.api._
import com.github.badoualy.telegram.tl.exception.RpcErrorException

/**
  * Actor for downloading media files. Saves them by their IDs into the specified directory.
  *
  * @param client client for downloading
  * @param dir directory to download files into
  */
class TgMediaDownloader(private val client: TelegramClient, private val dir: Path = Paths.get(".", "files")) extends Actor {
  private var lastRequest: Long = 0
  dir.toFile.mkdirs()

  private def waitCooldown(): Unit = {
    val cooldown: Long = 1000

    val currentTime = System.currentTimeMillis()
    if (currentTime - lastRequest < cooldown) Thread.sleep(cooldown - (currentTime - lastRequest))
    lastRequest = currentTime
  }

  override def receive: Receive = {
    // Case for downloading photos.
    case media: TLMessageMediaPhoto =>
      implicit val ec: ExecutionContextExecutor = context.dispatcher
      val file = dir.resolve(media.getPhoto.getId.toString).toFile
      val mediaInput = TLMediaUtilsKt.getAbsMediaInput(media)
      download(file, mediaInput)

    // Case for downloading files.
    case media: TLMessageMediaDocument =>
      implicit val ec: ExecutionContextExecutor = context.dispatcher
      media.getDocument match {
        case document: TLDocument =>
          val file = dir.resolve(document.getId.toString).toFile
          val mediaInput: MediaInput = TLMediaUtilsKt.getAbsMediaInput(media)
          download(file, mediaInput)
        case _ =>
      }

    case MsgTgClose =>
      client.close()
      context.stop(self)
  }

  private def download(file: File, mediaInput: MediaInput): Unit = {
    if(!file.exists()) {
      try {
        val stream = new FileOutputStream(file)
        waitCooldown()
        client.downloadSync(mediaInput.getInputFileLocation, mediaInput.getSize, stream)
        stream.close()
      } catch {
        case e: IOException =>
          if (file.exists()) file.delete()
          throw e
        case e: RpcErrorException =>
          if (file.exists()) file.delete()
          throw e
      }
    }
  }
}