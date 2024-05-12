import actors.BrainDrill
import actors.BrainDrill.TaskResult
import actors.BrainDrill.In.AssignTask
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

object Main:

  def main(args: Array[String]): Unit =
    given brainDrill: ActorSystem[BrainDrill.In] = ActorSystem(BrainDrill(), "braindrill")
    given timeout: Timeout = Timeout(5.seconds)
    given ec: ExecutionContextExecutor = brainDrill.executionContext

    val route =
      pathPrefix("lang" / Segment): lang =>
        post:
          entity(as[String]): code =>
              val asyncExecutionResponse = brainDrill
                .ask[TaskResult](AssignTask(code, lang, _))
                .map(_.output)
                .recover(_ => "something went wrong") // TODO: make better recovery

              complete(asyncExecutionResponse)

    Http()
      .newServerAt("localhost", 8080)
      .bind(route)
      .foreach(_ => brainDrill.log.info(s"Server now online"))
