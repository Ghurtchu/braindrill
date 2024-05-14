package loadbalancer

import cluster.ClusterBootstrap
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
import workers.helpers.DockerImagePuller

import scala.concurrent.duration.*
import scala.util.{Failure, Success}

object LoadBalancer:

  // incoming messages
  enum In:
    // received when workers are updated on any node
    case WorkersUpdated(newDrills: Set[ActorRef[Worker.StartExecution]])
    // ???
    case ImagePullersUpdated(newImagePullers: Set[ActorRef[DockerImagePuller.In.PullDockerImage]])
    // received from http layer to assign task to any worker on any node
    case AssignTask(code: String, language: String, replyTo: ActorRef[TaskResult])
    // received when worker responds with success
    case TaskSucceeded(output: String)
    // received when worker responds with failure
    case TaskFailed(reason: String)

  // final outgoing message to http layer
  final case class TaskResult(output: String)

  def apply(): Behavior[In] = Behaviors.setup: ctx =>
    ctx.setLoggerName("LoadBalancer")

    // adapter for subscribing for receptionist messages
    val adapter = ctx.messageAdapter[Receptionist.Listing]:
      case Worker.WorkerServiceKey.Listing(workers) =>
        In.WorkersUpdated(workers)
      case DockerImagePuller.DockerImagePullerServiceKey.Listing(imagePullers) =>
        In.ImagePullersUpdated(imagePullers)

    // subscribing for any changes represented by Worker.WorkerServiceKey
    ctx.system.receptionist ! Receptionist.Subscribe(Worker.WorkerServiceKey, adapter)
    // subscribing for any changes represented by DockerImagePuller.DockerImagePullerServiceKey
    ctx.system.receptionist ! Receptionist.Subscribe(DockerImagePuller.DockerImagePullerServiceKey, adapter)

    behavior(ctx)

  private def behavior(
    ctx: ActorContext[In],
    workers: Seq[ActorRef[Worker.StartExecution]] = Seq.empty, // # of workers
    imagePullers: Seq[ActorRef[DockerImagePuller.In.PullDockerImage]] = Seq.empty, // ???
    replyTo: Option[ActorRef[TaskResult]] = None // # http layer actor to which LoadBalancer replies
  ): Behavior[In] =
    Behaviors.receiveMessage[In]:
       // if workers are updated
      case In.WorkersUpdated(newWorkers) =>
        ctx.log.info("{} received WorkersUpdated. New workers: {}", ctx.self.path.name, newWorkers)

        // update state by copying them
        behavior(ctx, newWorkers.toSeq)

        // if asked to assign task
      case msg @ In.AssignTask(code, lang, replyTo) =>
        ctx.log.info("{} received {}", ctx.self.path.name, msg)
        given timeout: Timeout = Timeout(5.seconds)
        // choose random worker
        val worker: ActorRef[StartExecution] = workers(scala.util.Random.nextInt(workers.size))
        ctx.log.infoN("{} sending Worker.StartExecution to {}", ctx.self.path.name, worker.ref.path.name)
        // send StartExecution to worker and send to self TaskSucceeded or TaskFailed
        ctx.ask[Worker.StartExecution, Worker.ExecutionResult](worker, Worker.StartExecution(code, lang, _)):
          case Success(res) => In.TaskSucceeded(res.value)
          case Failure(exception) => In.TaskFailed(exception.toString)

        // registering Some(replyTo)
        behavior(ctx, workers, Seq.empty, Some(replyTo))

      case msg @ In.ImagePullersUpdated(imagePullers) =>
        ctx.log.infoN("{} sending PullDockerImage to each DockerImagePuller", ctx.self.path.name)
        ClusterBootstrap
          .LanguageToDockerImage
          .values
          .zip(imagePullers)
          .foreach { case (image, actor) =>
            ctx.log.infoN("{} sending PullDockerImage to each {}", ctx.self.path.name, actor.path.name)
            actor ! DockerImagePuller.In.PullDockerImage(image)
          }

        Behaviors.same
        // if task succeeded return TaskResult
      case In.TaskSucceeded(output) =>
        ctx.log.info("{} got output: {}", ctx.self.path.name, output)
        replyTo.foreach(_ ! TaskResult(output))

        Behaviors.same

      // if task failed return TaskResult
      case In.TaskFailed(reason) =>
        ctx.log.info("{} failed due to: {}", ctx.self.path.name, reason)
        replyTo.foreach(_ ! TaskResult(reason))

        Behaviors.same