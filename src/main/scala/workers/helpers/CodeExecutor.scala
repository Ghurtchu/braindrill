package workers.helpers

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import workers.Worker
import workers.Worker.{ExecutionFailed, ExecutionSucceeded}

import java.io.{BufferedReader, File}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

// Actor that the file inside docker container and returns process output
object CodeExecutor:

  // incoming messages
  enum In:
    // received from FileHandler to run the docker process
    case Execute(dockerImage: String, compiler: String, file: File, replyTo: ActorRef[Worker.In])

  // sent back to FileHandler
  case class Executed(output: String, exitCode: Int)

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import Worker.*
    import ctx.executionContext

    ctx.log.info(s"processing $msg")
    msg match
        // in case Execute is receied
      case In.Execute(dockerImage, compiler, file, replyTo) =>
        // create docker run commands
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

        val cmd = Array(
          compiler,
          file.getName
        )
        println(s"[commands]: ${cmd.toList}")

        val asyncExecutionResult = for
          ps <- execute(cmd) // start docker process
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
  def execute(commands: Array[String])(using ec: ExecutionContext) =
    Future(Runtime.getRuntime.exec(commands))

  // reads code output
  def read(reader: BufferedReader)(using ec: ExecutionContext) =
    Future:
      Using.resource(reader): reader =>

        @tailrec
        def loop(accumulated: String = ""): String =
          readLine(reader) match
            case Some(line) => loop(s"$accumulated$line\n")
            case None => accumulated

        loop()

  // reads single line
  def readLine(reader: BufferedReader) =
    Try(reader.readLine)
      .toOption
      .filter(line => line != null && line.nonEmpty)

