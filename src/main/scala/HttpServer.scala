import actors.BrainDrill
import actors.BrainDrill.ExecutionResponse
import actors.BrainDrill.In.InitiateExecution
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
    // top level actor - /user/braindrill
    given brainDrill: ActorSystem[BrainDrill.In] = ActorSystem(BrainDrill(), "braindrill")
    // 5 seconds timeout for ask pattern
    given timeout: Timeout = Timeout(5.seconds)
    // execution context for Future-s
    given ec: ExecutionContextExecutor = brainDrill.executionContext

    // HTTP route definition
    val route =
      // send HTTP request at e.g http://braindrill.dev/lang/python
      pathPrefix("lang" / Segment): lang =>
        // handle POST request
        post:
          // read source code from the request body
          entity(as[String]): code =>
              val asyncExecutionResponse = brainDrill // ask master
                .ask[ExecutionResponse](InitiateExecution(code, lang, _)) // to initiate code execution task
                .map(_.output) // and finally take "output" field
                .recover(_ => "something went wrong") // TODO: make better recovery

              complete(asyncExecutionResponse)

    // run HTTP server and start accepting requests
    Http()
      .newServerAt("localhost", 8080)
      .bind(route)
      .foreach(_ => brainDrill.log.info(s"Server now online. Please navigate to http://localhost:8080/hello\nPress RETURN to stop..."))
