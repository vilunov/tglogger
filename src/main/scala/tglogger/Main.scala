package tglogger

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsValueMarshaller
import spray.json._
import spray.json.DefaultJsonProtocol._
import tglogger.db.DBHandler

import scala.concurrent.{ExecutionContextExecutor, Future}

object Main extends App {
  if (args.length == 1) {
    args(0) match {
      case "init" => init()
      case "list-chans" => list_chans()
    }
    System.exit(0)
  }

  /// Init DB connection and most important actors
  DBHandler.connect()
  implicit val system: ActorSystem = ActorSystem("tglogger")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val execContext: ExecutionContextExecutor = system.dispatcher
  val route: Route =
    get {
      complete(DBHandler.getPubChannels.toJson)
    }

  val bf: Future[ServerBinding] = Http().bindAndHandle(route, "localhost", 8080)

  /// Init Telegram connection an client
  implicit val session: TgSession = new TgSession
  SessionLoader.loadSession()
  val tgHandler: ActorRef = system.actorOf(Props(new TgHandler(session)), "tgHandler")
  tgHandler ! MsgTgUpdateChannels
  tgHandler ! MsgTgClose

  def init(): Unit = {
    DBHandler.connect()
    DBHandler.initSchema()
  }

  def list_chans(): Unit = {
    DBHandler.connect()
    DBHandler.getPubChannels.foreach(println(_))
  }
}