import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.Behaviors
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.server.Directives._

import BrainDrill.Result._

import scala.util._

object HttpServer:

  def main(args: Array[String]): Unit =

    // actor system - "master" for processing incoming http requests
    implicit val actorSystem = ActorSystem(Behaviors.empty, "master")
    import actorSystem.log

    implicit val executionContext = actorSystem.executionContext //

    val route =
      pathPrefix("lang" / Segment): (lang: String) =>
        post:
          entity(as[String]): code =>
            onComplete(BrainDrill.execute(lang, code)):
              case Success(Executed(output, _)) =>
                complete(200, output)
              case Success(UnsupportedLanguage(lang)) =>
                complete(200, s"$lang is unsupported")
              case Failure(reason) =>
                complete(500, "Something went wrong")


    Http()
      .newServerAt("localhost", 8080)
      .bind(route)
      .foreach(_ => log.info(s"Server now online. Please navigate to http://localhost:8080/hello\nPress RETURN to stop..."))
