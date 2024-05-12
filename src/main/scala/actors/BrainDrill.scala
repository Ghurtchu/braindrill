package actors

import FileHandler.In.PrepareFile
import BrainDrill.In
import BrainDrill.TaskSucceeded
import org.apache.pekko.actor.typed.receptionist.{Receptionist, ServiceKey}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.File
import scala.util.*

/**
 * Root actor: assigning tasks to children and responding back to http layer
 */
object BrainDrill:

  val WorkerServiceKey = ServiceKey[BrainDrill.AssignTask]("BrainDrill")

  sealed trait In

  case class AssignTask(code: String, language: String, replyTo: ActorRef[TaskResult]) extends In with CborSerializable
  case class TaskSucceeded(result: String) extends In with CborSerializable
  case class TaskFailed(reason: String) extends In with CborSerializable

  case class TaskResult(output: String) extends CborSerializable

  private case class ExecutionInputs(dockerImage: String, compiler: String, extension: String)

  private val mappings: Map[String, ExecutionInputs] =
    Map(
      "python" -> ExecutionInputs(
        dockerImage = "python:3",
        compiler = "python",
        extension = ".py"
      ),
      "javascript" -> ExecutionInputs(
        dockerImage = "node:14",
        compiler = "node",
        extension = ".js"
      )
    )

  def apply(requester: Option[ActorRef[TaskResult]] = None): Behavior[In] =
    Behaviors.setup[In]: ctx =>
      ctx.log.info(s"${ctx.self} started")
      ctx.log.info("Registering myself with receptionist")
      ctx.system.receptionist ! Receptionist.Register(WorkerServiceKey, ctx.self)

      Behaviors.receiveMessage[In]:
        case msg @ AssignTask(code, lang, replyTo) =>
          ctx.log.info(s"processing $msg")
          mappings.get(lang) match
            case Some(inputs) =>
              val fileHandler = ctx.spawn(
                behavior = FileHandler(),
                name = s"file-handler"
              )
              ctx.log.info(s"sending PrepareFile to $fileHandler")
              fileHandler ! FileHandler.In.PrepareFile(
                name = s"$lang${Random.nextLong}${inputs.extension}", // random number for avoiding file overwrite/shadowing
                dockerImage = inputs.dockerImage,
                compiler = inputs.compiler,
                code = code,
                replyTo = ctx.self
              )
            case None =>
              val warning = s"unsupported language: $lang"
              ctx.log.warn(warning)
              replyTo ! TaskResult(warning)

          apply(requester = Some(replyTo))

        case TaskSucceeded(result) =>
          ctx.log.info(s"responding to initiator with successful ExecutionResponse: $result")
          requester.foreach(_ ! TaskResult(result))

          apply(requester = None)

        case TaskFailed(reason) =>
          ctx.log.warn(s"responding to initiator with failed ExecutionResponse: $reason")
          requester.foreach(_ ! TaskResult(s"execution failed due to: $reason"))

          apply(requester = None)

