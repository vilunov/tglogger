import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Paths}

import com.github.badoualy.telegram.api.{Kotlogram, TelegramApp, TelegramClient}
import com.github.badoualy.telegram.tl.api.auth.TLAuthorization
import com.github.badoualy.telegram.tl.exception.RpcErrorException

import Vars._

object Main {
  def main(args: Array[String]): Unit = {
    loadSession()
    val app = new TelegramApp(ApiId, ApiHash, SessionName, "1", "1", "en")
    val client = Kotlogram.getDefaultClient(app, Session)
    auth(client)
    saveSession()
  }

  /**
    * Reads the file with session information
    */
  def loadSession(): Unit = {
    if (new File(SessionFilePath).exists()) {
      val s = Files.readAllBytes(Paths.get(SessionFilePath))
      Session.deserialize(s)
    }
  }

  /**
    * Saves the session information into the file
    */
  def saveSession(): Unit = {
    val fos = new FileOutputStream(SessionFilePath)
    try {
      fos.write(Session.serialize().toArray)
    } finally {
      fos.close()
    }
  }

  /**
    * If needed, ask user for login inputs and authenticate them
    *
    * @param client client instance
    */
  def auth(client: TelegramClient): Unit = {
    import scala.io.StdIn.readLine

    if (Session.key.isDefined) return

    print("Input phone number: ")
    val phone = readLine()

    val sentCode = client.authSendCode(false, phone, true)
    print("Input authentication code: ")
    val code = readLine()

    val auth: TLAuthorization = try
      client.authSignIn(phone, sentCode.getPhoneCodeHash, code)
    catch {
      case e: RpcErrorException if e.getTag == "SESSION_PASSWORD_NEEDED" => {
        // Login failed due to two-step authorization
        // Ask user for password and try again
        print("Insert password: ")
        val pwd = readLine()
        client.authCheckPassword(pwd)
      }
    }

    val self = auth.getUser.getAsUser
    println(s"You are signed in as ${self.getFirstName} ${self.getLastName}")
  }
}
