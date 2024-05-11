import actors.Master
import actors.Master.Out.ExecutionResponse
import actors.Master.In.InitiateExecution
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import pekko.actor.typed.scaladsl.AskPattern.Askable

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*

object HttpServer:

  def main(args: Array[String]): Unit =
    // master actor
    implicit val master = ActorSystem(Master(), "braindrill")
    // 5 seconds timeout for ask pattern
    implicit val timeout: Timeout = Timeout(5.seconds)
    // execution context for Future-s
    given ec: ExecutionContextExecutor = master.executionContext

    import master.log

    // HTTP route definition
    val route =
      // send HTTP request at e.g http://braindrill.dev/lang/python
      pathPrefix("lang" / Segment): lang =>
        // handle POST request
        post:
          // read source code from the request body
          entity(as[String]): code =>
              val asyncExecutionResponse = master // ask master
                .ask[Master.Out](InitiateExecution(code, lang, _)) // to initiate code execution task task
                .mapTo[ExecutionResponse] // then map the result to ExecutionResponse
                .map(_.output) // and finally take "output" field
                .recover(_ => "something went wrong") // TODO: make better recovery

              complete(asyncExecutionResponse)

    // run HTTP server and start accepting requests
    Http()
      .newServerAt("localhost", 8080)
      .bind(route)
      .foreach(_ => log.info(s"Server now online. Please navigate to http://localhost:8080/hello\nPress RETURN to stop..."))
