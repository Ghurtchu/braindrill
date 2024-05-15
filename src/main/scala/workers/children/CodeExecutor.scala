package workers.children

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import workers.Worker
import workers.Worker.{ExecutionFailed, ExecutionSucceeded}

import java.io.{BufferedReader, File}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

// Actor that executes the submitted code and returns the success/failed output
object CodeExecutor:

  // incoming messages
  enum In:
    // received from FileHandler to run the docker process
    case Execute(compiler: String, file: File, replyTo: ActorRef[Worker.In])

  // sent back to FileHandler
  case class Executed(output: String, exitCode: Int)

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import Worker.*
    import ctx.executionContext
    
    msg match
        // in case Execute is receied
      case In.Execute(compiler, file, replyTo) =>
        ctx.log.info(s"{} execuding submitted code", ctx.self.path.name)
        // create docker run commands
        val asyncExecutionResult = for
          ps <- execute(Array(compiler, file.getName)) // start docker process
          (asyncSuccess, asyncError) = read(ps.inputReader) -> read(ps.errorReader) // read success and error concurrently
          ((success, error), exitCode) <- asyncSuccess.zip(asyncError).zip(Future(ps.waitFor)) // join success, error and exit code
          _ = Future(file.delete) // remove file in the background to free up the memory
        yield Executed(
          output = if success.nonEmpty then success else error,
          exitCode = exitCode
        )

        asyncExecutionResult.onComplete:
            // in case code executes without issues
          case Success(Executed(output, _)) =>
            // respond back normally
            replyTo ! ExecutionSucceeded(output)
            // otherwise
          case Failure(t) =>
            // indicate why it failed
            replyTo ! ExecutionFailed(t.toString)

        Behaviors.stopped

  // starts docker process
  private def execute(commands: Array[String])(using ec: ExecutionContext) =
    Future(Runtime.getRuntime.exec(commands))

  // reads code output
  private def read(reader: BufferedReader)(using ec: ExecutionContext) =
    Future:
      Using.resource(reader): reader =>

        @tailrec
        def loop(accumulated: String = ""): String =
          readLine(reader) match
            case Some(line) => loop(s"$accumulated$line\n")
            case None => accumulated

        loop()

  // reads single line
  private def readLine(reader: BufferedReader) =
    Try(reader.readLine)
      .toOption
      .filter(line => line != null && line.nonEmpty)

