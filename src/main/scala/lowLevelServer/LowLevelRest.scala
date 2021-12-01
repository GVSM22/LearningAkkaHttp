package lowLevelServer

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import lowLevelServer.GuitarDb.{CreateGuitar, FindAllGuitars, FindGuitar, GuitarCreated}
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps

case class Guitar(make: String, model: String, quantity: Int = 0)
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
    case FetchAvailable(available) =>
      val status = if (available) "in stock" else "out of stock"
      val filtering: Guitar => Boolean = if (available) (g: Guitar) => g.quantity > 0 else (g: Guitar) => g.quantity == 0
      log.info(s"Searching for guitars $status")
      sender() ! guitars.values.filter(filtering).toList
    case AddGuitarInStock(id, quantity) =>
      guitars.get(id) match {
        case Some(guitar) =>
          log.info(s"Adding $quantity ${guitar.model} models to stock")
          val updatedGuitar = guitar.copy(quantity = guitar.quantity + quantity)
          sender() ! updatedGuitar
          val updatedMap = guitars + (id -> updatedGuitar)
          context.become(receiveGuitars(updatedMap, currentId))
      }
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
  case class FetchAvailable(available: Boolean)
  case class AddGuitarInStock(id: Int, quantity: Int)
  case object FindAllGuitars
}

trait GuitarStoreJsonProtocol extends DefaultJsonProtocol {

  implicit val guitarFormat: RootJsonFormat[Guitar] = jsonFormat3(Guitar)
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
      |  "model": "Stratocaster",
      |  "quantity": 0
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

  implicit val defaultTimeout: Timeout = Timeout(2 seconds)

  def getGuitar(query: Query): Future[HttpResponse] = {
    query.get("id").map(_.toInt) match {
      case Some(id) =>
        (dbActor ? FindGuitar(id)).mapTo[Option[Guitar]].map {
          case Some(guitar) => HttpResponse(entity = HttpEntity(
            ContentTypes.`application/json`,
            guitar.toJson.prettyPrint
          ))
          case None => HttpResponse(status = StatusCodes.NotFound)
        }
      case None => Future(HttpResponse(status = StatusCodes.NotFound))
    }
  }

  def getGuitarInStock(query: Query): Future[HttpResponse] = {
    query.get("inStock").map(_.toBoolean) match {
      case Some(available) =>
        (dbActor ? GuitarDb.FetchAvailable(available)).mapTo[List[Guitar]].map{ guitars =>
          HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, guitars.toJson.prettyPrint))
        }
      case None => Future(HttpResponse(StatusCodes.NotFound))
    }
  }

  def setGuitarInStock(query: Query): Future[HttpResponse] = {
    (query.get("id").map(_.toInt), query.get("quantity").map(_.toInt)) match {
      case (Some(id), Some(quantity)) =>
        (dbActor ? GuitarDb.AddGuitarInStock(id, quantity)).mapTo[Guitar].map{ guitarAdded =>
          HttpResponse(entity = HttpEntity(
            ContentTypes.`application/json`,
            guitarAdded.toJson.prettyPrint
          ))
        }
      case _ => Future(HttpResponse(StatusCodes.NotFound))
    }
  }
  /*
    server code
    HttpRequest => Future[HttpResponse]
   */
  val requestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar"), _, _, _) =>
      val query = uri.query() // query object <=> Map[String, String]
      query match {
        case Query.Empty =>
          val guitarsFuture = (dbActor ? FindAllGuitars).mapTo[List[Guitar]]
          guitarsFuture.map{ guitars =>
            HttpResponse(
              entity = HttpEntity(
                ContentTypes.`application/json`,
                guitars.toJson.prettyPrint
              )
            )
          }
        case query => getGuitar(query)
      }
    case HttpRequest(HttpMethods.POST, Uri.Path("/api/guitar"), _, entity, _) =>
      val strictEntity = entity.toStrict(3 seconds)
      for {
        resolvedEntity <- strictEntity
        guitar = resolvedEntity.data.utf8String.parseJson.convertTo[Guitar]
        _ = (dbActor ? CreateGuitar(guitar)).mapTo[GuitarCreated]
      } yield HttpResponse()
    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      uri.query() match {
        case Query.Empty => Future(HttpResponse(StatusCodes.NotFound))
        case query => getGuitarInStock(query)
      }
    case HttpRequest(HttpMethods.POST, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      uri.query() match {
        case Query.Empty => Future(HttpResponse(StatusCodes.NotFound))
        case query => setGuitarInStock(query)
      }
    case request: HttpRequest =>
      // if we do not include a case for other requests, Akka-Streams will backpressure and propagate it to all TCP-Layer
      request.discardEntityBytes()
      Future(HttpResponse(status = StatusCodes.NotFound))
  }

  Http().bindAndHandleAsync(requestHandler, "localhost", 8080)
  // TODO - tudo pronto, só terminar a aula e commitar!
}
