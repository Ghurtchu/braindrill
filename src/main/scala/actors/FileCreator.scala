package actors

import FileCreator.Command.CreateFile
import Master.In
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.{File, PrintWriter}
import scala.concurrent.Future
import scala.util.*


object FileCreator:

  enum Command:
    case CreateFile(
      name: String,
      code: String,
      dockerImage: String,
      compiler: String,
      replyTo: ActorRef[Master.In]
    )

  def apply() = Behaviors.receive[Command]: (ctx, msg) =>
    import ctx.executionContext

    msg match
      case Command.CreateFile(name, code, dockerImage, compiler, replyTo) =>
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