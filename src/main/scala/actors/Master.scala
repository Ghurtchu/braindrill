package actors

import FileCreator.In.CreateFile
import Master.In
import Master.In.ExecutionSucceeded
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.File
import scala.util.*

// This actor never terminates as it acts as a load balancer for worker actors
object Master {

  // incoming messages
  enum In:
    // command from http layer
    case InitiateExecution(code: String, language: String, replyTo: ActorRef[ExecutionResponse])
    // response from FileCreator actor
    case FileCreated(file: File, dockerImage: String, compiler: String, replyTo: ActorRef[Master.In])
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

    Behaviors.receive[In]:
      (ctx, msg) =>
        msg match
          case In.InitiateExecution(code, lang, replyTo) =>
            val fileCreator = ctx.spawn(
              behavior = FileCreator(),
              name = s"file-creator-${Random.nextLong}"
            )
            mappings.get(lang) match
              case Some(inputs) =>
                ctx.log.info(s"sending CreateFile to $fileCreator")
                fileCreator ! FileCreator.In.CreateFile(
                  name = s"$lang${Random.nextLong}${inputs.extension}",
                  dockerImage = inputs.dockerImage,
                  compiler = inputs.compiler,
                  code = code,
                  replyTo = ctx.self
                )
              case None => replyTo ! ExecutionResponse(s"unsupported language: $lang")

            setInitiator(replyTo)

          case msg: In.FileCreated =>
            ctx.log.info(s"received $msg")
            val codeExecutor = ctx.spawn(
              behavior = CodeExecutor(),
              name = s"code-executor-${Random.nextLong}"
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
            codeExecutor ! CodeExecutor.In.Execute(commands, msg.file, msg.replyTo)
            unchanged

          case In.ExecutionSucceeded(result) =>
            initiator.foreach(_ ! ExecutionResponse(result))
            clearState

          case In.ExecutionFailed(reason) =>
            initiator.foreach(_ ! ExecutionResponse(s"execution failed due to: $reason"))
            clearState
}

