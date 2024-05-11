package actors

import CodeExecutor.Result.{Executed, UnsupportedLang}
import Master.In
import Master.In.ExecutionOutput
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.pattern.*

import java.io.{BufferedReader, File}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

object CodeExecutor {

  enum Result:
    case Executed(output: String, exitCode: Int)
    case UnsupportedLang(lang: String)

  // simple model for grouping inputs to execute code inside container
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

  enum In:
    case Execute(commands: Array[String], file: File, replyTo: ActorRef[Master.In])

  def apply(): Behavior[In] =
    Behaviors.receive[In]:
      (ctx, msg) =>

        import ctx.executionContext

        msg match
          case In.Execute(commands, file, replyTo) =>
            val asyncExecutionResult = for
              // run docker container
              ps <- execute(commands)
              // start reading error and success channels concurrently
              (success, error) = read(ps.inputReader) -> read(ps.errorReader)
              // join Futures of success, error and exitCode
              ((success, error), exitCode) <- success.zip(error).zip(Future(ps.waitFor))
              // free up the memory
              _ = Future(file.delete)
            yield Result.Executed(
              output = if success.nonEmpty then success else error,
              exitCode = exitCode
            )

            asyncExecutionResult.onComplete:
              case Success(Executed(output, _)) =>
                replyTo ! Master.In.ExecutionOutput(output)
              case Success(UnsupportedLang(lang)) =>
                replyTo ! Master.In.ExecutionOutput(s"unsupported lang: $lang")
              case Failure(t) =>
                replyTo ! Master.In.ExecutionFailed(t.toString)

            Behaviors.same

  // starts the process in async way
  private def execute(commands: Array[String])(using ec: ExecutionContext) =
    Future(Runtime.getRuntime.exec(commands))

  // reads the full output from source in async way with resource-safety enabled
  private def read(reader: BufferedReader)(using ec: ExecutionContext) =
    Future:
      Using.resource(reader): reader =>

        @tailrec
        def loop(accumulated: String = ""): String =
          readLine(reader) match
            case Some(line) => loop(s"$accumulated$line\n")
            case None => accumulated

        loop()

  // reads a single line from source with optionality in mind
  private def readLine(reader: BufferedReader) =
    Try(reader.readLine)
      .toOption
      .filter(line => line != null && line.nonEmpty)
}

