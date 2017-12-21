package tglogger

import com.github.badoualy.telegram.api.TelegramApiStorage
import com.github.badoualy.telegram.mtproto.auth.AuthKey
import com.github.badoualy.telegram.mtproto.model.{DataCenter, MTSession}

class TgSession extends TelegramApiStorage {
  var key: Option[AuthKey] = None
  var dc: Option[DataCenter] = None
  var session: Option[MTSession] = None

  override def loadAuthKey(): AuthKey = key.orNull

  override def loadDc(): DataCenter = dc.orNull

  override def loadSession(): MTSession = session.orNull

  override def deleteAuthKey(): Unit = key = None

  override def deleteDc(): Unit = dc = None

  override def saveAuthKey(key: AuthKey): Unit = this.key = Option(key)

  override def saveDc(dc: DataCenter): Unit = this.dc = Option(dc)

  override def saveSession(session: MTSession): Unit = this.session = Option(session)
}
