package workers

import BrainDrill.In
import BrainDrill.TaskSucceeded
import workers.CodeExecutor.Out.Executed
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

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import ctx.executionContext
    import BrainDrill.*

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
          s"$dockerImage",
          s"$compiler",
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
            replyTo ! TaskSucceeded(output)
          case Failure(t) =>
            replyTo ! TaskFailed(t.toString)

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

