package loadbalancer

import workers.Worker
import workers.Worker.In
import workers.Worker.StartExecution
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

    behavior(ctx)

  private def behavior(
    ctx: ActorContext[In],
    workers: Seq[ActorRef[Worker.StartExecution]] = Seq.empty, // # of workers
    replyTo: Option[ActorRef[TaskResult]] = None // # http layer actor to which LoadBalancer replies
  ): Behavior[In] =
    Behaviors.receiveMessage[In]:
       // if workers are updated
      case In.WorkersUpdated(newWorkers) =>
        ctx.log.info("List of services registered with the receptionist changed: {}", newWorkers)

        // update state by copying them
        behavior(ctx, newWorkers.toSeq)

        // if asked to assign task
      case In.AssignTask(code, lang, replyTo) =>
        given timeout: Timeout = Timeout(5.seconds)
        // choose random worker
        val worker: ActorRef[StartExecution] = workers(scala.util.Random.nextInt(workers.size))
        ctx.log.info("sending work for processing to {}", worker)
        // send StartExecution to worker and send to self TaskSucceeded or TaskFailed
        ctx.ask[Worker.StartExecution, Worker.ExecutionResult](worker, Worker.StartExecution(code, lang, _)):
          case Success(res) => In.TaskSucceeded(res.value)
          case Failure(exception) => In.TaskFailed(exception.toString)

        // registering Some(replyTo)
        behavior(ctx, workers, Some(replyTo))

        // if task succeeded return TaskResult
      case In.TaskSucceeded(output) =>
        ctx.log.info("Got output: {}", output)
        replyTo.foreach(_ ! TaskResult(output))

        Behaviors.same

      // if task failed return TaskResult
      case In.TaskFailed(reason) =>
        ctx.log.warn("Failed due to: {}", reason)
        replyTo.foreach(_ ! TaskResult(reason))

        Behaviors.same