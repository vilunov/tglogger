package tglogger

import scala.concurrent.{ExecutionContextExecutor, Future}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsValueMarshaller
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import spray.json._
import spray.json.DefaultJsonProtocol._

import tglogger.db.DBHandler
import tglogger.entities._
import tglogger.Vars.Http._

object Main extends App {
  if (args.length == 1) {
    args(0) match {
      case "init" => db.Schema.initSchema()
      case "list-chans" => list_chans()
    }
    System.exit(0)
  }

  /// Init DB connection and most important actors
  DBHandler.connect()
  implicit val system: ActorSystem = ActorSystem("tglogger")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val execContext: ExecutionContextExecutor = system.dispatcher

  implicit val channelFormat: RootJsonFormat[Channel] = jsonFormat3(Channel)
  implicit val messageFormat: RootJsonFormat[Message] = jsonFormat3(Message)

  val route: Route =
    get {
      path("chans") {
        complete(DBHandler.getPubChannels.map(_.toJson))
      } ~
      path("messages" / IntNumber / IntNumber) { (channel, fromId) =>
        complete(DBHandler.isPubChannel(channel).map {
          case Some(b) if b =>
            ToResponseMarshallable(DBHandler.getMessages(channel, fromId).map(_.toJson))
          case Some(_) =>
            ToResponseMarshallable("permission denied")
          case None =>
            ToResponseMarshallable("no such channel")
        })
      }
    }

  val bf: Future[ServerBinding] = Http().bindAndHandle(route, Interface, Port)

  /// Init Telegram connection an client
  implicit val session: TgSession = new TgSession
  SessionLoader.loadSession()
  val tgHandler: ActorRef = system.actorOf(Props(new TgHandler(session)), "tgHandler")
  tgHandler ! MsgTgUpdateChannels
  tgHandler ! MsgTgGetAllMessages

  def list_chans(): Unit = {
    DBHandler.connect()
    DBHandler.getPubChannelsIds.foreach(println(_))
  }
}