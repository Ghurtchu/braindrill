package loadbalancer

import workers.Worker
import workers.Worker.{StartExecution, ExecutionResult}
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
import scala.util.{Failure, Random, Success}
import Random.shuffle

object LoadBalancer:

  // incoming messages
  enum In:
    // received when workers are updated on any node
    case WorkersUpdated(newDrills: Set[ActorRef[Worker.StartExecution]])
    // received from http layer to assign task to any worker on any node
    case AssignTask(code: String, language: String, replyTo: ActorRef[TaskResult])
    // received when worker responds with success
    case TaskSucceeded(output: String)
    // received when worker responds with failure
    case TaskFailed(reason: String)

  // final outgoing message to http layer
  final case class TaskResult(output: String)

  def apply(): Behavior[In] = Behaviors.setup: ctx =>
    // adapter for subscribing for receptionist messages
    val adapter = ctx.messageAdapter[Receptionist.Listing]:
      case Worker.WorkerServiceKey.Listing(workers) =>
        In.WorkersUpdated(workers)

    // subscribing for any changes represented by Worker.WorkerServiceKey
    ctx.system.receptionist ! Receptionist.Subscribe(Worker.WorkerServiceKey, adapter)

    behavior(ctx, Seq.empty, None)

  private def behavior(
    ctx: ActorContext[In],
    workers: Seq[ActorRef[Worker.StartExecution]], // # of workers
    replyTo: Option[ActorRef[TaskResult]]// # http layer root actor to which LoadBalancer replies
  ): Behavior[In] =
    Behaviors.receiveMessage[In]:
      case In.WorkersUpdated(updatedWorkers) =>
        ctx.log.info("{} received WorkersUpdated. New workers: {}", ctx.self.path.name, updatedWorkers)

        behavior(ctx, updatedWorkers.toSeq, None)
      case msg @ In.AssignTask(code, lang, replyTo) =>
        ctx.log.info("{} received {}", ctx.self.path.name, msg)
        given timeout: Timeout = Timeout(3.seconds)
        val worker = shuffle(workers).head
        ctx.log.infoN("{} sending Worker.StartExecution to {}", ctx.self.path.name, worker.path.name)

        // ask worker Worker.StartExecution and expect to receive Worker.ExecutionResult
        // then map this to TaskSucceeded or TaskFailed messages
        ctx.ask[StartExecution, ExecutionResult](worker, StartExecution(code, lang, _)):
          case Success(res)       => In.TaskSucceeded(res.value)
          case Failure(exception) => In.TaskFailed(exception.toString)

        behavior(ctx, workers, Some(replyTo))

      case In.TaskSucceeded(output) =>
        ctx.log.info("{} got output: {}", ctx.self.path.name, output)
        replyTo.foreach(_ ! TaskResult(output))

        Behaviors.same

      case In.TaskFailed(reason) =>
        ctx.log.info("{} failed due to: {}", ctx.self.path.name, reason)
        replyTo.foreach(_ ! TaskResult(reason))

        Behaviors.same