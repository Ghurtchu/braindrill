import actors.BrainDrill
import actors.BrainDrill.{In, TaskResult}
import actors.BrainDrill.In.AssignTask
import org.apache.pekko
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.util.Timeout
import pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import pekko.actor.typed.scaladsl.AskPattern.Askable

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

object Main:

  object Requester {

    enum Event {
      case BrainDrillsUpdated(newDrills: Set[ActorRef[BrainDrill.In.AssignTask]])
      case AssignTask(code: String, language: String, replyTo: ActorRef[Any])
      case TaskSucceeded(output: String)
      case TaskFailed(why: String)
    }

    def apply(): Behavior[Event] = Behaviors.setup { ctx =>

      val adapter = ctx.messageAdapter[Receptionist.Listing] {
        case BrainDrill.WorkerServiceKey.Listing(workers) =>
          Event.BrainDrillsUpdated(workers)
      }

      ctx.system.receptionist ! Receptionist.Subscribe(BrainDrill.WorkerServiceKey, adapter)

      running(ctx, Seq.empty)
    }

    private def running(ctx: ActorContext[Event], workers: Seq[ActorRef[BrainDrill.In.AssignTask]]): Behavior[Event] = {

      Behaviors.receiveMessage[Event] {

        case Event.BrainDrillsUpdated(newWorkers) =>
          ctx.log.info("List of services registered with the receptionist changed: {}", newWorkers)

          running(ctx, newWorkers.toSeq)

        case Event.AssignTask(code, lang, replyTo) =>
          given timeout: Timeout = Timeout(5.seconds)
          val worker: ActorRef[In.AssignTask] = workers.head
          ctx.log.info("sending work for processing to {}", worker)
          ctx.ask[In.AssignTask, TaskResult](worker, In.AssignTask(code, lang, _)) {
            case Success(res) => Event.TaskSucceeded(res.output)
            case Failure(exception) => Event.TaskFailed(exception.toString)
          }
          running(ctx, workers)

        case Event.TaskSucceeded(output) =>
          ctx.log.info("Got output: {}", output)
          Behaviors.same

        case Event.TaskFailed(why) =>
          ctx.log.info("Failed due to: {}", why)
          Behaviors.same
      }
    }



  }

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
