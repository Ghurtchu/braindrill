package actors

import FileCreator.In.CreateFile
import BrainDrill.In
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.{File, PrintWriter}
import scala.concurrent.Future
import scala.util.*


object FileCreator:

  // incoming messages
  enum In:
    case CreateFile(
      name: String,
      code: String,
      dockerImage: String,
      compiler: String,
      replyTo: ActorRef[BrainDrill.In]
    )

  def apply() = Behaviors.receive[In]: (ctx, msg) =>
    import ctx.executionContext

    msg match
      case In.CreateFile(name, code, dockerImage, compiler, replyTo) =>
        ctx.log.info("processing CreateFile")
        val asyncFile = for
          // create file
          file <- Future(File(name))
          // put code inside file with resource-safety enabled
          _    <- Future(Using.resource(PrintWriter(name))(_.write(code)))
        yield file

        // once finished
        asyncFile.foreach: file =>
          // reply FileCreated to Master actor
          replyTo ! BrainDrill.In.FileCreated(
            file = file,
            dockerImage = dockerImage,
            compiler = compiler,
            replyTo = replyTo
          )

      ctx.log.info(s"stopping ${ctx.self}")
      // stop the actor, free up the memory
      Behaviors.stopped