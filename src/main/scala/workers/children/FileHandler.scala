package workers.children

import org.apache.pekko.actor.typed.{ActorRef, Terminated}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import workers.children.FileHandler.In.PrepareFile
import workers.Worker

import java.io.{File, PrintWriter}
import scala.concurrent.Future
import scala.util.*


// Actor that prepares the file for running it later inside docker container
object FileHandler:

  // incoming messages
  enum In:
    // received from Worker to prepare the file for executing it later
    case PrepareFile(
      name: String,
      code: String,
      dockerImage: String,
      compiler: String,
      replyTo: ActorRef[Worker.In]
    )

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import CodeExecutor.In.*
    import ctx.executionContext

    ctx.log.info(s"processing $msg")
    msg match
        // prepared from Worker to prepare file
      case In.PrepareFile(name, code, dockerImage, compiler, replyTo) =>
        val asyncFile = for
          file <- Future(File(name)) // create file
          _    <- Future(Using.resource(PrintWriter(name))(_.write(code))) // write code to it
        yield file
        
        // create code executor actor
        val codeExecutor = ctx.spawn(CodeExecutor(), "code-executor")
        // observe it for self-destruction later when the child stops
        ctx.watch(codeExecutor)

        // send Execute command to it
        asyncFile.foreach: file =>
          codeExecutor ! Execute(
            dockerImage = dockerImage,
            compiler = compiler,
            file = file,
            replyTo = replyTo
          )

        Behaviors.same // state unchanged
  .receiveSignal:
      // in case Terminated is received
    case (ctx, Terminated(ref)) =>
      ctx.log.info(s"$ref was stopped, stopping myself too: ${ctx.self}")
      // stop self
      Behaviors.stopped
