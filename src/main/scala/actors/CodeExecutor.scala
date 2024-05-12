package actors

import BrainDrill.In
import BrainDrill.In.TaskSucceeded
import actors.CodeExecutor.Out.Executed
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.{BufferedReader, File}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

/**
 * Runs the file inside docker container and returns process output
 */
object CodeExecutor:

  enum In:
    case Execute(dockerImage: String, compiler: String, file: File, replyTo: ActorRef[BrainDrill.In])

  enum Out:
    case Executed(output: String, exitCode: Int)

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

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import ctx.executionContext

    ctx.log.info(s"processing $msg")
    msg match
      case In.Execute(dockerImage, compiler, file, replyTo) =>
        val commands = Array(
          "docker",
          "run",
          "--rm", // remove container after it's done
          "-v",
          s"${System.getProperty("user.dir")}:/app",
          "-w",
          "/app",
          s"${dockerImage}",
          s"${compiler}",
          s"${file.getName}",
        )

        val asyncExecutionResult = for
          ps <- execute(commands)
          (asyncSuccess, asyncError) = read(ps.inputReader) -> read(ps.errorReader)
          ((success, error), exitCode) <- asyncSuccess.zip(asyncError).zip(Future(ps.waitFor))
          _ = Future(file.delete)
        yield Out.Executed(
          output = if success.nonEmpty then success else error,
          exitCode = exitCode
        )

        asyncExecutionResult.onComplete:
          case Success(Out.Executed(output, _)) =>
            replyTo ! BrainDrill.In.TaskSucceeded(output)
          case Failure(t) =>
            replyTo ! BrainDrill.In.TaskFailed(t.toString)

        Behaviors.stopped

  private def execute(commands: Array[String])(using ec: ExecutionContext) =
    Future(Runtime.getRuntime.exec(commands))

  private def read(reader: BufferedReader)(using ec: ExecutionContext) =
    Future:
      Using.resource(reader): reader =>

        @tailrec
        def loop(accumulated: String = ""): String =
          readLine(reader) match
            case Some(line) => loop(s"$accumulated$line\n")
            case None => accumulated

        loop()

  private def readLine(reader: BufferedReader) =
    Try(reader.readLine)
      .toOption
      .filter(line => line != null && line.nonEmpty)

