package workers.children

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.IOResult
import org.apache.pekko.stream.scaladsl.{Sink, Source, StreamConverters}
import org.apache.pekko.util.ByteString
import workers.Worker

import java.io.{BufferedReader, File, InputStream}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

// Actor that executes the submitted code and returns the success/failed output
object CodeExecutor:

  val ReadOutput: Sink[ByteString, Future[String]] = Sink.fold[String, ByteString]("")(_ + _.utf8String)

  enum In:
    case Execute(compiler: String, file: File, replyTo: ActorRef[Worker.In])
    case Executed(output: String, exitCode: Int, replyTo: ActorRef[Worker.In])
    case ExecutionFailed(why: String, replyTo: ActorRef[Worker.In])
    case ExecutionSucceeded(output: String, replyTo: ActorRef[Worker.In])

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import Worker.*
    import ctx.executionContext
    import ctx.system

    val selfName = ctx.self

    msg match
      case In.Execute(compiler, file, replyTo) =>
        ctx.log.info(s"{} executing submitted code", selfName)
        val asyncExecuted: Future[In.Executed] = for
          ps <- execute("timeout", "2", compiler, file.getName) // start process with 2 seconds timeout
          (successSource, errorSource) = src(ps.getInputStream) -> src(ps.getErrorStream) // stream representation of error and success channels
          ((success, error), exitCode)  <- successSource.runWith(ReadOutput) // join success, error and exitCode
            .zip(errorSource.runWith(ReadOutput))
            .zip(Future(ps.waitFor))
          _ = Future(file.delete) // remove file in the background to free up the memory
        yield In.Executed(
          output = if success.nonEmpty then success else error,
          exitCode = exitCode,
          replyTo = replyTo
        )

        ctx.pipeToSelf(asyncExecuted):
          case Success(executed)  =>
            ctx.log.info("[SUCCESS]: {}", executed)
            executed.exitCode match
              case 124 => In.ExecutionFailed("The process was aborted because it exceeded the timeout", replyTo)
              case 137 => In.ExecutionFailed("The process was aborted because it exceeded the memory usage", replyTo)
              case _   => In.ExecutionSucceeded(executed.output, replyTo)
          case Failure(exception) =>
            ctx.log.info("[FAILURE]: {}", exception)
            In.ExecutionFailed(exception.getMessage, replyTo)

        Behaviors.same

      case In.ExecutionSucceeded(output, replyTo) =>
        ctx.log.info(s"{} executed submitted code successfully", selfName)
        replyTo ! Worker.ExecutionSucceeded(output)

        Behaviors.stopped

      case In.ExecutionFailed(why, replyTo) =>
        ctx.log.warn(s"{} execution failed due to {}", selfName ,why)
        replyTo ! Worker.ExecutionFailed(why)

        // stopping myself, CodeExecutor should decide what to do
        Behaviors.stopped
  private def src(is: => InputStream): Source[ByteString, Future[IOResult]] =
    StreamConverters.fromInputStream(() => is)

  // starts process
  private def execute(commands: String*)(using ec: ExecutionContext) =
    Future(sys.runtime.exec(commands.toArray))
