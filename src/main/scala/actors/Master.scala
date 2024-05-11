package actors

import FileCreator.In.CreateFile
import Master.In
import Master.In.ExecutionOutput
import Master.Out.Response
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.File
import scala.util.*

object Master {

  enum In:
    // command from http layer
    case StartExecution(code: String, language: String, replyTo: ActorRef[Any])
    // response from FileCreator actor
    case FileCreated(file: File, dockerImage: String, compiler: String, replyTo: ActorRef[Master.In])
    // response-1 from CodeExecutor actor
    case ExecutionOutput(result: String)
    // response-2 from CodeExecutor actor
    case ExecutionFailed(reason: String)

  enum Out:
    case Response(result: String)

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

  def apply(initiator: Option[ActorRef[Any]] = None): Behavior[In] =
    lazy val clearState = apply(initiator = None)
    lazy val setInitiator: ActorRef[Any] => Behavior[In] = sender => apply(Some(sender))
    lazy val unchanged = Behaviors.same[In]

    Behaviors.receive[In]:
      (ctx, msg) =>
        msg match
          case In.StartExecution(code, lang, replyTo) =>
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
              case None => replyTo ! s"unsupported language: $lang"

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

          case In.ExecutionOutput(result) =>
            initiator.foreach(_ ! Out.Response(result))
            clearState

          case In.ExecutionFailed(reason) =>
            initiator.foreach(_ ! Out.Response(s"execution failed due to: $reason"))
            clearState
}

