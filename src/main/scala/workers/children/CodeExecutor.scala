package workers.children

import org.apache.pekko.actor.{ActorSystem, ClassicActorSystemProvider}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.pattern
import workers.Worker
import workers.children.CodeExecutor.In

import java.io.{BufferedReader, File}
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}
import scala.util.*

// Actor that executes the submitted code and returns the success/failed output
object CodeExecutor:

  // incoming messages
  enum In:
    // received from FileHandler to run the docker process
    case Execute(compiler: String, file: File, replyTo: ActorRef[Worker.In])
    // piped to self if execution is successful
    case Executed(output: String, exitCode: Int, replyTo: ActorRef[Worker.In])
    // piped to self if execution is failed
    case ExecutionFailed(why: String, replyTo: ActorRef[Worker.In])

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import Worker.*
    import ctx.executionContext

    val selfName = ctx.self
    given system: ActorSystem = ctx.system.classicSystem

    msg match
      case In.Execute(compiler, file, replyTo) =>
        ctx.log.info(s"{} executing submitted code", selfName)
        val asyncExecuted: Future[In.Executed] = for
          ps <- execute(Array("timeout", "2", compiler, file.getName)) // start process with 2 seconds timeout
          (asyncSuccess, asyncError) = read(ps.inputReader) -> read(ps.errorReader) // read success and error concurrently
          ((success, error), exitCode)  <- asyncSuccess.zip(asyncError).zip(Future(ps.waitFor)) // join success, error and exitCode
          _ = Future(file.delete) // remove file in the background to free up the memory
        yield In.Executed(
          output = if success.nonEmpty then success else error,
          exitCode = exitCode,
          replyTo = replyTo
        )

        ctx.pipeToSelf(asyncExecuted):
          case Success(executed)  =>
            executed.exitCode match
              case 124 => In.ExecutionFailed("The process was terminated because it exceeded the timeout", replyTo)
              case _   => executed
          case Failure(exception) =>
            In.ExecutionFailed(exception.toString, replyTo)

        Behaviors.same

      case In.Executed(output, exitCode, replyTo) =>
        ctx.log.info(s"{} executed submitted code successfully", selfName)
        replyTo ! Worker.ExecutionSucceeded(output)

        Behaviors.stopped

      case msg @ In.ExecutionFailed(why, replyTo) =>
        ctx.log.warn(s"{} execution failed due to {}", selfName ,why)
        replyTo ! Worker.ExecutionFailed(why)

        // stopping myself, CodeExecutor should decide what to do
        Behaviors.stopped

  // starts process
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

