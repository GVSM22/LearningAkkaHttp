package lowLevelServer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.IncomingConnection
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import scala.language.postfixOps

object LowLevelAPI extends App {

  implicit val system = ActorSystem("LowLevelServerAPI")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val serverSource = Http().bind("localhost", 8000)
  val connectionSink = Sink.foreach[IncomingConnection] { conn =>
    println(s"Accepted incoming connection from: ${conn.remoteAddress}")
  }

//  serverSource.to(connectionSink)
//    .run()
//    .onComplete {
//      case Success(binding) =>
//        println("Server binding successful")
//        binding.terminate(2 seconds)
//      case Failure(exception) => println(s"Server binding failure: $exception")
//    }

  /*
    1 - Synchronously serve HTTP responses
   */

  val syncRequestHandler: HttpRequest => HttpResponse = {
    case HttpRequest(HttpMethods.GET, _, _, _, _) =>
      HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |     Hello from Akka HTTP!
            | </body>
            |</html>
            """.stripMargin
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(
        StatusCodes.NotFound,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |     OOPS! The resource can't be found
            | </body>
            |</html>
            """.stripMargin
        )
      )
  }

  val httpSyncConnectionHandler = Sink.foreach[IncomingConnection] { conn =>
    conn.handleWithSyncHandler(syncRequestHandler)
  }

//  Http().bind("localhost", 8000).runWith(httpSyncConnectionHandler)
//  Http().bindAndHandleSync(syncRequestHandler, "localhost", 8000)

  /*
    2 - Async handler
   */

  val asyncRequestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, Uri.Path("/home"), _, _, _) =>
      Future(HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |     Hello from Akka HTTP!
            | </body>
            |</html>
            """.stripMargin
        )
      ))
    case request: HttpRequest =>
      request.discardEntityBytes()
      Future(HttpResponse(
        StatusCodes.NotFound,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |     OOPS! The resource can't be found
            | </body>
            |</html>
            """.stripMargin
        )
      ))
  }

  val httpAsyncConnectionHandler = Sink.foreach[IncomingConnection] { conn =>
    conn.handleWithAsyncHandler(asyncRequestHandler)
  }
//  Http().bind("localhost", 8000).runWith(httpAsyncConnectionHandler)
//  Http().bindAndHandleAsync(asyncRequestHandler, "localhost", 8000)

  /*
    3 - async via Streams
   */

  val streamsBasedRequest: Flow[HttpRequest, HttpResponse, _] = Flow[HttpRequest].map {
    case HttpRequest(HttpMethods.GET, Uri.Path("/home"), _, _, _) =>
      HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |     Hello from Akka HTTP!
            | </body>
            |</html>
            """.stripMargin
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(
        StatusCodes.NotFound,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |     OOPS! The resource can't be found
            | </body>
            |</html>
            """.stripMargin
        )
      )
  }

//  Http().bind("localhost", 8000).runForeach(conn => conn.handleWith(streamsBasedRequest))
//  Http().bindAndHandle(streamsBasedRequest, "localhost", 8000)


  /*
  Exercise: run on localhost:8388, which replies
    - with a welcome message on the "front door"
    - with a proper HTML on /about
    - 404 otherwise
   */

  Http()
    .bindAndHandle(Flow[HttpRequest].map[HttpResponse]{
      case HttpRequest(HttpMethods.GET, Uri.Path("/about"), _, _, _) =>
        HttpResponse(entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |     This is an exercise made for RockTheJVM!
            | </body>
            |</html>
            """.stripMargin
        ))

      case HttpRequest(HttpMethods.GET, Uri.Empty, _, _, _) =>
        HttpResponse(entity = HttpEntity("Hello!"))
      case _ => HttpResponse(StatusCodes.NotFound)
    }, "localhost", 8388)
}
