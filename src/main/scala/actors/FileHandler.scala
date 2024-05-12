package actors

import FileHandler.In.PrepareFile
import BrainDrill.In
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.Terminated

import java.io.{File, PrintWriter}
import scala.concurrent.Future
import scala.util.*


object FileHandler:

  enum In:
    case PrepareFile(
      name: String,
      code: String,
      dockerImage: String,
      compiler: String,
      replyTo: ActorRef[BrainDrill.In]
    )

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import ctx.executionContext

    ctx.log.info(s"processing $msg")
    msg match
      case In.PrepareFile(name, code, dockerImage, compiler, replyTo) =>
        val asyncFile = for
          file <- Future(File(name))
          _    <- Future(Using.resource(PrintWriter(name))(_.write(code)))
        yield file

        val executor = ctx.spawn(CodeExecutor(), "code-executor")
        // observing child actor for self-destruction
        ctx.watch(executor)

        asyncFile.foreach: file =>
          executor ! CodeExecutor.In.Execute(
            dockerImage = dockerImage,
            compiler = compiler,
            file = file,
            replyTo = replyTo
          )

        Behaviors.same
  .receiveSignal:
    case (ctx, Terminated(ref)) =>
      ctx.log.info(s"$ref was stopped, stopping myself too: ${ctx.self}")
      Behaviors.stopped
