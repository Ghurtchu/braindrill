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
import scala.util.control.NoStackTrace

object CodeExecutor:

  private val KiloByte = 1024 // 1024 bytes
  private val MegaByte = KiloByte * KiloByte // 1,048,576 bytes
  private val TwoMegabytes = 2 * MegaByte // 2,097,152 bytes

  private val AdjustedMaxSizeInBytes =
    (TwoMegabytes * 20) / 100 // 419,430 bytes, which is approx 409,6 KB or 0.4 MB

  // max size of output when the code is run, if it exceeds the limit then we let the user know to reduce logs or printing
  private val MaxOutputSize = AdjustedMaxSizeInBytes

  enum In:
    case Execute(compiler: String, file: File, dockerImage: String, replyTo: ActorRef[Worker.In])
    case Executed(output: String, exitCode: Int, replyTo: ActorRef[Worker.In])
    case ExecutionFailed(why: String, replyTo: ActorRef[Worker.In])
    case ExecutionSucceeded(output: String, replyTo: ActorRef[Worker.In])

  private case object TooLargeOutput extends Throwable with NoStackTrace {
    override def getMessage: String =
      "the code is generating too large output, try reducing logs or printing"
  }

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import Worker.*
    import ctx.executionContext
    import ctx.system

    val self = ctx.self

    msg match
      case In.Execute(compiler, file, dockerImage, replyTo) =>
        ctx.log.info(s"{}: executing submitted code", self)
        val asyncExecuted: Future[In.Executed] = for
          // timeout --signal=SIGKILL 2 docker run --rm --ulimit cpu=1 --memory=20m -v engine:/data -w /data rust rust /data/r.rust
          ps <- run(
            "timeout",
            "--signal=SIGKILL",
            "3", // 2 second timeout which sends SIGKILL if exceeded
            "docker",
            "run",
            "--rm", // remove the container when it's done
            "--ulimit", // set limits
            "cpu=2", // 2 processors
            "--memory=50m", // 50 M of memory
            "-v", // bind volume
            "engine:/data",
            "-w", // set working directory to /data
            "/data",
            dockerImage,
            compiler,
            s"${file.getPath}"
          )
          // error and success channels as streams
          (successSource, errorSource) = src(ps.getInputStream) -> src(ps.getErrorStream)
          ((success, error), exitCode) <- successSource
            .runWith(readOutput) // join success, error and exitCode
            .zip(errorSource.runWith(readOutput))
            .zip(Future(ps.waitFor))
          _ = Future(file.delete) // remove file in the background to free up the memory
        yield In.Executed(
          output = if success.nonEmpty then success else error,
          exitCode = exitCode,
          replyTo = replyTo
        )

        ctx.pipeToSelf(asyncExecuted):
          case Success(executed) =>
            ctx.log.info("{}: executed submitted code", self)
            executed.exitCode match
              case 124 | 137 =>
                In.ExecutionFailed(
                  "The process was aborted because it exceeded the timeout",
                  replyTo
                )
              case 139 =>
                In.ExecutionFailed(
                  "The process was aborted because it exceeded the memory usage",
                  replyTo
                )
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
        ctx.log.warn(s"{}: execution failed due to {}", self, why)
        replyTo ! Worker.ExecutionFailed(why)

        Behaviors.stopped

  private def readOutput(using ec: ExecutionContext): Sink[ByteString, Future[String]] =
    Sink
      .fold[String, ByteString]("")(_ + _.utf8String)
      .mapMaterializedValue:
        _.flatMap: str =>
          if str.length > MaxOutputSize then Future failed TooLargeOutput
          else Future successful str

  private def src(stream: => InputStream): Source[ByteString, Future[IOResult]] =
    StreamConverters.fromInputStream(() => stream)

  private def run(commands: String*)(using ec: ExecutionContext) =
    Future(sys.runtime.exec(commands.toArray))
