package workers.children

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.IOResult
import org.apache.pekko.stream.scaladsl.{Sink, Source, StreamConverters}
import org.apache.pekko.util.ByteString
import workers.Worker

import java.io.{File, InputStream}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

object CodeExecutor:

  private val ReadOutput: Sink[ByteString, Future[String]] = Sink.fold[String, ByteString]("")(_ + _.utf8String)

  enum In:
    case Execute(compiler: String, file: File, dockerImage: String, replyTo: ActorRef[Worker.In])
    case Executed(output: String, exitCode: Int, replyTo: ActorRef[Worker.In])
    case ExecutionFailed(why: String, replyTo: ActorRef[Worker.In])
    case ExecutionSucceeded(output: String, replyTo: ActorRef[Worker.In])

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import Worker.*
    import ctx.executionContext
    import ctx.system

    val self = ctx.self

    msg match
      case In.Execute(compiler, file, dockerImage, replyTo) =>
        ctx.log.info(s"{}: executing submitted code", self)
        val asyncExecuted: Future[In.Executed] = for
          // just copies file there
          ps <- run(
            "docker",
            "run",
            "--rm", // remove the container when it's done
            "--ulimit", // set limits
            "cpu=1", // 1 processor
            "--memory=20m", // 20 M of memory
            "-v", // bind volume
            "engine:/data",
            "-w", // set working directory to /data
            "/data",
            dockerImage,
            compiler,
            s"${file.getPath}",
          )
          (successSource, errorSource) = src(ps.getInputStream) -> src(ps.getErrorStream) // error and success channels as streams
          ((success, error), exitCode) <- successSource.runWith(ReadOutput) // join success, error and exitCode
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
            ctx.log.info("{}: executed submitted code", self)
            executed.exitCode match
              case 124       => In.ExecutionFailed("The process was aborted because it exceeded the timeout", replyTo)
              case 137 | 139 => In.ExecutionFailed("The process was aborted because it exceeded the memory usage", replyTo)
              case _ => In.ExecutionSucceeded(executed.output, replyTo)

          case Failure(exception) =>
            ctx.log.warn("{}: execution failed due to {}", self, exception.getMessage)
            In.ExecutionFailed(exception.getMessage, replyTo)

        Behaviors.same

      case In.ExecutionSucceeded(output, replyTo) =>
        ctx.log.info(s"{}: executed submitted code successfully", self)
        replyTo ! Worker.ExecutionSucceeded(output)

        Behaviors.stopped

      case In.ExecutionFailed(why, replyTo) =>
        ctx.log.warn(s"{}: execution failed due to {}", self ,why)
        replyTo ! Worker.ExecutionFailed(why)

        Behaviors.stopped
  private def src(stream: => InputStream): Source[ByteString, Future[IOResult]] =
    StreamConverters.fromInputStream(() => stream)

  private def run(commands: String*)(using ec: ExecutionContext) =
    Future(sys.runtime.exec(commands.toArray))
