package actors

import FileHandler.In.PrepareFile
import BrainDrill.In
import BrainDrill.In.TaskSucceeded
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.File
import scala.util.*

object BrainDrill:

  enum In:
    case AssignTask(code: String, language: String, replyTo: ActorRef[TaskResult])
    case TaskSucceeded(result: String)
    case TaskFailed(reason: String)

  case class TaskResult(output: String)

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

  def apply(initiator: Option[ActorRef[TaskResult]] = None): Behavior[In] =
    Behaviors.setup[In]: ctx =>
      ctx.log.info(s"${ctx.self} started")

      Behaviors.receiveMessage[In]:
        case msg @ In.AssignTask(code, lang, replyTo) =>
          ctx.log.info(s"processing $msg")
          val fileHandler = ctx.spawn(
            behavior = FileHandler(),
            name = s"file-handler"
          )
          mappings.get(lang) match
            case Some(inputs) =>
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

          apply(Some(replyTo))

        case In.TaskSucceeded(result) =>
          ctx.log.info("responding to initiator with successful ExecutionResponse")
          initiator.foreach(_ ! TaskResult(result))
          apply(initiator = None)

        case In.TaskFailed(reason) =>
          ctx.log.info("responding to initiator with failed ExecutionResponse")
          initiator.foreach(_ ! TaskResult(s"execution failed due to: $reason"))
          apply(initiator = None)

