package lowLevelServer

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import lowLevelServer.GuitarDb.{CreateGuitar, FindAllGuitars, GuitarCreated}
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps

case class Guitar(make: String, model: String)
class GuitarDb extends Actor with ActorLogging {
  import GuitarDb._
  override def receive: Receive = receiveGuitars()
  def receiveGuitars(guitars: Map[Int, Guitar] = Map(), currentId: Int = 0): Receive = {
    case FindAllGuitars =>
      log.info("Searching for all guitars")
      sender() ! guitars.values.toList
    case FindGuitar(id) =>
      log.info(s"Searching guitar id: $id")
      sender() ! guitars.get(id)
    case CreateGuitar(guitar) =>
      log.info(s"Adding guitar: $guitar with id $currentId")
      sender() ! GuitarCreated(currentId)
      context.become(receiveGuitars(guitars ++ Map(currentId -> guitar), currentId + 1))
  }
}

object GuitarDb {
  case class CreateGuitar(guitar: Guitar)
  case class GuitarCreated(id: Int)
  case class FindGuitar(id: Int)
  case object FindAllGuitars
}

trait GuitarStoreJsonProtocol extends DefaultJsonProtocol {

  implicit val guitarFormat: RootJsonFormat[Guitar] = jsonFormat2(Guitar)
}

object LowLevelRest extends App with GuitarStoreJsonProtocol {

  implicit val system: ActorSystem = ActorSystem("LowLevelServerAPI")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher // use another EC in real life code

  /*
    GET on /api/guitar => ALL the guitars in the store
    POST on /api/guitar => insert the guitar on the body into the store
   */

  // JSON -> marshalling
  val simpleGuitar = Guitar("Fender", "Stratocaster")
  println(simpleGuitar.toJson.prettyPrint)

  val simpleGuitarJson =
    """
      |{
      |  "make": "Fender",
      |  "model": "Stratocaster"
      |}
    """.stripMargin
  println(simpleGuitarJson.parseJson.convertTo[Guitar])

  val dbActor = system.actorOf(Props[GuitarDb], "LowLevelGuitarDB")
  val guitarList = List(
    Guitar("Fender", "Stratocaster"),
    Guitar("Gibson", "Les Paul"),
    Guitar("Martin", "LX1")
  )
  guitarList.foreach(dbActor ! CreateGuitar(_))
  /*
    server code
    HttpRequest => Future[HttpResponse]
   */
  implicit val defaultTimeout: Timeout = Timeout(2 seconds)
  val requestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, Uri.Path("/api/guitar"), _, _, _) =>
      val guitarsFuture = (dbActor ? FindAllGuitars).mapTo[List[Guitar]]
      guitarsFuture.map{ guitars =>
          HttpResponse(
            entity = HttpEntity(
              ContentTypes.`application/json`,
              guitars.toJson.prettyPrint
            )
          )
      }
    case HttpRequest(HttpMethods.POST, Uri.Path("/api/guitar"), _, entity, _) =>
      val strictEntity = entity.toStrict(3 seconds)
      for {
        resolvedEntity <- strictEntity
        guitar = resolvedEntity.data.utf8String.parseJson.convertTo[Guitar]
        _ = (dbActor ? CreateGuitar(guitar)).mapTo[GuitarCreated]
      } yield HttpResponse()
    case request: HttpRequest =>
      // if we do not include a case for other requests, Akka-Streams will backpressure and propagate it to all TCP-Layer
      request.discardEntityBytes()
      Future(HttpResponse(status = StatusCodes.NotFound))
  }

  Http().bindAndHandleAsync(requestHandler, "localhost", 8080)

}
