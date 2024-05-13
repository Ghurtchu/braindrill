package loadbalancer

import workers.BrainDrill
import workers.BrainDrill.{In, TaskResult}
import workers.BrainDrill.AssignTask
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import pekko.actor.typed.{ActorRef, Behavior}
import pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.util.Timeout
import pekko.actor.typed.*
import pekko.actor.typed.scaladsl.*

import scala.concurrent.duration.*
import scala.util.{Failure, Success}

object LoadBalancer:

  enum In:
    case BrainDrillsUpdated(newDrills: Set[ActorRef[BrainDrill.AssignTask]])
    case AssignTask(code: String, language: String, replyTo: ActorRef[TaskResult])
    case TaskSucceeded(output: String)
    case TaskFailed(why: String)

  final case class TaskResult(output: String)

  def apply(): Behavior[In] = Behaviors.setup: ctx =>
    val adapter = ctx.messageAdapter[Receptionist.Listing]:
      case BrainDrill.WorkerServiceKey.Listing(workers) =>
        In.BrainDrillsUpdated(workers)

    ctx.system.receptionist ! Receptionist.Subscribe(BrainDrill.WorkerServiceKey, adapter)

    behavior(ctx, Seq.empty)

  private def behavior(
    ctx: ActorContext[In],
    workers: Seq[ActorRef[BrainDrill.AssignTask]],
    replyTo: Option[ActorRef[TaskResult]] = None
  ): Behavior[In] =
    Behaviors.receiveMessage[In]:
      case In.BrainDrillsUpdated(newWorkers) =>
        ctx.log.info("List of services registered with the receptionist changed: {}", newWorkers)

        behavior(ctx, newWorkers.toSeq)

      case In.AssignTask(code, lang, replyTo) =>
        given timeout: Timeout = Timeout(5.seconds)
        val worker: ActorRef[AssignTask] = workers.head
        ctx.log.info("sending work for processing to {}", worker)
        ctx.ask[BrainDrill.AssignTask, BrainDrill.TaskResult](worker, BrainDrill.AssignTask(code, lang, _)):
          case Success(res) => In.TaskSucceeded(res.output)
          case Failure(exception) => In.TaskFailed(exception.toString)

        behavior(ctx, workers, Some(replyTo))

      case In.TaskSucceeded(output) =>
        ctx.log.info("Got output: {}", output)
        replyTo.foreach(_ ! TaskResult(output))

        Behaviors.same

      case In.TaskFailed(why) =>
        ctx.log.info("Failed due to: {}", why)
        replyTo.foreach(_ ! TaskResult(why))

        Behaviors.same