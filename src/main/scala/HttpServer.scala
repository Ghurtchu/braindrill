import actors.Master
import actors.Master.Out
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.*
import pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import pekko.actor.typed.scaladsl.AskPattern.Askable

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.*
import scala.util.*

object HttpServer:

  def main(args: Array[String]): Unit =
    // actor system - "master" for processing incoming http requests
    implicit val master = ActorSystem( Master(), "master")
    // 5 seconds timeout
    implicit val timeout: Timeout = Timeout(5.seconds)
    // execution context for running Future-s
    given ec: ExecutionContextExecutor = master.executionContext
    // actors logger
    import master.log

    // HTTP route definition
    val route: Route =
      // send HTTP request at e.g http://braindrill.dev/lang/python
      pathPrefix("lang" / Segment): lang =>
        // handle POST request
        post:
          // read source code from the request body
          entity(as[String]): code =>
            complete:
              master
                .ask(replyTo => Master.In.StartExecution(code, lang, replyTo))
                .mapTo[Master.Out]
                .map { case Out.Response(result) => result }
                .recover(_ => "something went wrong")

    // run HTTP server and start accepting requests
    Http()
      .newServerAt("localhost", 8080)
      .bind(route)
      .foreach(_ => log.info(s"Server now online. Please navigate to http://localhost:8080/hello\nPress RETURN to stop..."))
