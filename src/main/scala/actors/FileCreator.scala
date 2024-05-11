package actors

import CodeExecutor.Result.{Executed, UnsupportedLang}
import FileCreator.In.CreateFile
import Master.In
import Master.In.ExecutionOutput
import Master.Out.Response
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.pattern.*

import java.io.{BufferedReader, File, PrintWriter}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*


object FileCreator:
  enum In:
    case CreateFile(
      name: String,
      code: String,
      dockerImage: String,
      compiler: String,
      replyTo: ActorRef[Master.In]
    )

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import ctx.executionContext

    msg match
      case In.CreateFile(name, code, dockerImage, compiler, replyTo) =>
        val asyncFile = for
          file <- Future(File(name))
          _    <- Future(Using.resource(PrintWriter(name))(_.write(code)))
        yield file

        asyncFile.foreach: file =>
          replyTo ! Master.In.FileCreated(
            file = file,
            dockerImage = dockerImage,
            compiler = compiler,
            replyTo = replyTo
          )

    Behaviors.same