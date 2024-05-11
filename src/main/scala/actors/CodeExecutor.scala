package actors

import Master.In
import Master.In.ExecutionSucceeded
import actors.CodeExecutor.Out.Executed
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.{BufferedReader, File}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

object CodeExecutor:

  // incoming messages
  enum In:
    case Execute(commands: Array[String], file: File, replyTo: ActorRef[Master.In])

  // outgoing messages
  enum Out:
    case Executed(output: String, exitCode: Int)

  // simple model for grouping inputs to execute code inside container
  private case class ExecutionInputs(dockerImage: String, compiler: String, extension: String)

  // mapping language to its ExecutionInputs
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

    msg match
      case In.Execute(commands, file, replyTo) =>
        val asyncExecutionResult = for
          // run docker container
          ps <- execute(commands)
          // start reading error and success channels concurrently
          (asyncSuccess, asyncError) = read(ps.inputReader) -> read(ps.errorReader)
          // join Futures of success, error and exitCode
          ((success, error), exitCode) <- asyncSuccess.zip(asyncError).zip(Future(ps.waitFor))
          // free up the memory
          _ = Future(file.delete)
        yield Out.Executed(
          output = if success.nonEmpty then success else error,
          exitCode = exitCode
        )

        // once finished
        asyncExecutionResult.onComplete:
          // if succeeds
          case Success(Out.Executed(output, _)) =>
            // reply ExecutionSucceeded to Master
            replyTo ! Master.In.ExecutionSucceeded(output)
          // if fails
          case Failure(t) =>
            // reply ExecutionFailed to Master
            replyTo ! Master.In.ExecutionFailed(t.toString)

        // stop the actor, free up the memory
        Behaviors.stopped

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

