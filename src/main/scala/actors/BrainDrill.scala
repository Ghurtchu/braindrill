package actors

import FileCreator.In.CreateFile
import BrainDrill.In
import BrainDrill.In.ExecutionSucceeded
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.File
import scala.util.*

/**
 * BrainDrill is a master actor
 * BrainDrill never terminates as it acts as:
 * - top level actor
 * - load balancer between worker actors (FileCreator and CodeExecutor)
 */
object BrainDrill {

  // incoming messages
  enum In:
    // command from http layer
    case InitiateExecution(code: String, language: String, replyTo: ActorRef[ExecutionResponse])
    // response from FileCreator actor
    case FileCreated(file: File, dockerImage: String, compiler: String, replyTo: ActorRef[BrainDrill.In])
    // response-1 from CodeExecutor actor
    case ExecutionSucceeded(result: String)
    // response-2 from CodeExecutor actor
    case ExecutionFailed(reason: String)

  // reply to initiator actor from http layer
  case class ExecutionResponse(output: String)

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

  def apply(initiator: Option[ActorRef[ExecutionResponse]] = None): Behavior[In] =
    lazy val clearState = apply(initiator = None)
    lazy val setInitiator: ActorRef[ExecutionResponse] => Behavior[In] = sender => apply(Some(sender))
    lazy val unchanged = Behaviors.same[In]

    Behaviors.setup[In]: ctx =>
      ctx.log.info(s"${ctx.self} started")

      Behaviors.receiveMessage[In]:
        case msg @ In.InitiateExecution(code, lang, replyTo) =>
          ctx.log.info(s"processing $msg")
          val fileCreator = ctx.spawn(
            behavior = FileCreator(),
            name = s"file-creator"
          )
          mappings.get(lang) match
            case Some(inputs) =>
              ctx.log.info(s"sending CreateFile to $fileCreator")
              fileCreator ! FileCreator.In.CreateFile(
                name = s"$lang${Random.nextLong}${inputs.extension}", // random number for avoiding file overwrite
                dockerImage = inputs.dockerImage,
                compiler = inputs.compiler,
                code = code,
                replyTo = ctx.self
              )
            case None =>
              val warning = s"unsupported language: $lang"
              ctx.log.warn(warning)
              replyTo ! ExecutionResponse(warning)

          setInitiator(replyTo)

        case msg: In.FileCreated =>
          ctx.log.info(s"received $msg")
          val codeExecutor = ctx.spawn(
            behavior = CodeExecutor(),
            name = s"code-executor"
          )
          val commands = Array(
            "docker",
            "run",
            "--rm", // remove container after it's done
            "-v",
            s"${System.getProperty("user.dir")}:/app",
            "-w",
            "/app",
            s"${msg.dockerImage}",
            s"${msg.compiler}", // e.g scala
            s"${msg.file.getName}", // e.g Main.scala
          )
          ctx.log.info(s"sending Execute to $codeExecutor")
          codeExecutor ! CodeExecutor.In.Execute(commands, msg.file, msg.replyTo)
          unchanged

        case In.ExecutionSucceeded(result) =>
          ctx.log.info("responding to initiator with successful ExecutionResponse")
          initiator.foreach(_ ! ExecutionResponse(result))
          clearState

        case In.ExecutionFailed(reason) =>
          ctx.log.info("responding to initiator with failed ExecutionResponse")
          initiator.foreach(_ ! ExecutionResponse(s"execution failed due to: $reason"))
          clearState
}

