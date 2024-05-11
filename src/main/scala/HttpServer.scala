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
    // execution context for running Future-s
    implicit val executionContext = actorSystem.executionContext
    // actors logger
    import actorSystem.log

    // HTTP route definition
    val route =
      // send HTTP request at e.g http://execution-engine/lang/python
      pathPrefix("lang" / Segment): lang =>
        // handle POST request
        post:
          // read source code from the request body
          entity(as[String]): code =>
            // start executing code
            val drill = BrainDrill.runAsync(lang, code)
            // register callback for responding back to HTTP request
            onComplete(drill):
              case Success(Executed(output, _)) =>
                // send back the output from the process
                complete(200, output)
              case Success(UnsupportedLang(lang)) =>
                // notify user that the lang is unsupported
                complete(200, s"$lang is unsupported")
              case Failure(reason) =>
                // TODO: make fine grained message in case failures
                complete(500, "Something went wrong")


    // run HTTP server and start accepting requests
    Http()
      .newServerAt("localhost", 8080)
      .bind(route)
      .foreach(_ => log.info(s"Server now online. Please navigate to http://localhost:8080/hello\nPress RETURN to stop..."))
