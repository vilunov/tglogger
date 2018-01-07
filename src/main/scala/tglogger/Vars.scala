package tglogger

import com.typesafe.config._

object Vars {
  var config: Config = ConfigFactory.load()

  object TgClient {
    val ApiId: Int              = config.getInt   ("telegram.api_id")
    val ApiHash: String         = config.getString("telegram.api_hash")
    val SessionName: String     = config.getString("telegram.session_name")
    val SessionFilePath: String = config.getString("telegram.session_file_path")
    val DownloadMedia: Boolean  = try config.getBoolean("telegram.download_media")
      catch {
        case _: ConfigException.Missing => false
      }
  }

  object DB {
    val DBName: String   = config.getString("database.db")
    val User: String     = config.getString("database.user")
    val Password: String = config.getString("database.password")
    val Host: String     = config.getString("database.hostname")
    val ConnStr = s"jdbc:postgresql://$Host/$DBName"
  }
}
