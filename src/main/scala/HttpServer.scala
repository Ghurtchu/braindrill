import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.Behaviors
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.server.Directives._

import scala.util._

object HttpServer:

  def main(args: Array[String]): Unit =

    // actor system - "master" for processing incoming http requests
    implicit val actorSystem = ActorSystem(Behaviors.empty, "master")
    import actorSystem.log

    implicit val executionContext = actorSystem.executionContext //

    val route =
      path("hello"):
        get:
          onComplete(BrainDrill.execute):
            case Success((success, error, _)) =>
              complete(200, if success.nonEmpty then success else error)
            case Failure(reason) =>
              complete(200, reason.toString)

    val binding = Http().newServerAt("localhost", 8080).bind(route)

    log.info(s"Server now online. Please navigate to http://localhost:8080/hello\nPress RETURN to stop...")
