package workers.children

import org.apache.pekko.actor.typed.{ActorRef, Terminated}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.util.ByteString
import workers.children.FileHandler.In.PrepareFile
import workers.Worker

import java.io.File
import java.nio.file.Path
import scala.concurrent.Future
import scala.util.*


object FileHandler:

  enum In:
    case PrepareFile(name: String, code: String, compiler: String, replyTo: ActorRef[Worker.In])
    case FilePrepared(compiler: String, file: File, replyTo: ActorRef[Worker.In])
    case FilePreparationFailed(why: String, replyTo: ActorRef[Worker.In])

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import CodeExecutor.In.*
    import ctx.executionContext
    import ctx.system

    val self = ctx.self

    ctx.log.info(s"{}: processing {}", self, msg)

    msg match
      case In.PrepareFile(name, code, compiler, replyTo) =>
        val asyncFile = for
          file <- Future(File(s"/data/$name"))
          _    <- Source.single(code)
            .map(ByteString.apply)
            .runWith(FileIO.toPath(Path.of(s"/data/$name")))
        yield file

        ctx.pipeToSelf(asyncFile):
          case Success(file) => In.FilePrepared(compiler, file, replyTo)
          case Failure(why)  => In.FilePreparationFailed(why.getMessage, replyTo)

        Behaviors.same

      case In.FilePrepared(compiler, file, replyTo) =>
        val codeExecutor = ctx.spawn(CodeExecutor(), "code-executor")
        // observe child for self-destruction
        ctx.watch(codeExecutor)
        ctx.log.info("{} prepared file, sending Execute to {}", self, codeExecutor)
        codeExecutor ! Execute(compiler, file, replyTo)

        Behaviors.same

      case In.FilePreparationFailed(why, replyTo) =>
        ctx.log.warn(
          "{} failed during file preparation due to {}, sending ExecutionFailed to {}",
          self,
          why,
          replyTo
        )
        replyTo ! Worker.ExecutionFailed(why)

        Behaviors.stopped

  .receiveSignal:
    case (ctx, Terminated(ref)) =>
      ctx.log.info(s"{} is stopping because child actor: {} was stopped", ctx.self, ref)

      Behaviors.stopped
