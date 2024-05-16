package workers.children

import org.apache.pekko.actor.typed.{ActorRef, Terminated}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import workers.children.FileHandler.In.PrepareFile
import workers.Worker

import java.io.{File, PrintWriter}
import scala.concurrent.Future
import scala.util.*


// Actor that prepares the file for before running it
object FileHandler:

  // incoming messages
  enum In:
    // received from Worker to prepare the file for executing it later
    case PrepareFile(name: String, code: String, compiler: String, replyTo: ActorRef[Worker.In])
    case FilePrepared(compiler: String, file: File, replyTo: ActorRef[Worker.In])
    case FilePreparationFailed(why: String, replyTo: ActorRef[Worker.In])


  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import CodeExecutor.In.*
    import ctx.executionContext

    ctx.log.info(s"{}: processing {}", ctx.self.path.name, msg)
    msg match
        // prepared from Worker to prepare file
      case In.PrepareFile(name, code, compiler, replyTo) =>
        val asyncFile = for
          file <- Future(File(name)) // create file
          _    <- Future(Using.resource(PrintWriter(name))(_.write(code))) // write code to it
        yield file

        // pipe to self
        ctx.pipeToSelf(asyncFile):
          case Success(file) => In.FilePrepared(compiler, file, replyTo)
          case Failure(why)  => In.FilePreparationFailed(why.toString, replyTo)

        Behaviors.same

      case In.FilePrepared(compiler, file, replyTo) =>
        // create code executor actor
        val codeExecutor = ctx.spawn(CodeExecutor(), "code-executor")
        // observe it for self-destruction later when the child stops
        ctx.watch(codeExecutor)
        ctx.log.info("{} prepared file, sending Execute to {}", ctx.self.path.name, codeExecutor.path.name)
        codeExecutor ! Execute(compiler, file, replyTo)

        Behaviors.same // state unchanged

      case In.FilePreparationFailed(why, replyTo) =>
        ctx.log.warn(
          "{} failed during file preparation due to {}, sending ExecutionFailed to {}",
          ctx.self.path.name,
          why,
          replyTo.path.name
        )
        replyTo ! Worker.ExecutionFailed(why)

        // stopping actor, Worker should decide what to do
        Behaviors.stopped

  .receiveSignal:
      // in case Terminated is received
    case (ctx, Terminated(ref)) =>
      ctx.log.info(s"$ref was stopped, stopping myself too: ${ctx.self}")
      // stopping self, since child also stopped
      Behaviors.stopped
