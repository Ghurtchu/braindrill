package workers.helpers

import org.apache.pekko.actor.typed.receptionist.{Receptionist, ServiceKey}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.LoggerOps
import serialization.CborSerializable

import scala.concurrent.Future


object DockerImagePuller:

  // service key for Receptionist events
  val DockerImagePullerServiceKey = ServiceKey[DockerImagePuller.In.PullDockerImage]("DockerImagePuller")

  // incoming messages

  sealed trait In

  object In:
    case class PullDockerImage(image: String) extends In with CborSerializable

  def apply() = Behaviors.setup[In]: ctx =>
    import ctx.executionContext
    val selfName = ctx.self.path.name
    ctx.log.info(s"{} starting up, awaiting PullDockerImage message", selfName)

    ctx.log.info("registering myself: {} with Receptionist", selfName)
    // register to Receptionist so that LoadBalancer is updated with new worker references
    ctx.system.receptionist ! Receptionist.Register(DockerImagePullerServiceKey, ctx.self)

    Behaviors.receiveMessage[In]:
      case In.PullDockerImage(image) =>
        ctx.log.infoN("{} pulling {}", selfName, image)
        val commands = Array(
          "docker",
          "pull",
          image
        )

        val d = for
          ps <- CodeExecutor.execute(commands)
          (asyncSuccess, asyncError) = CodeExecutor.read(ps.inputReader) -> CodeExecutor.read(ps.errorReader)
          ((success, error), exitCode) <- asyncSuccess.zip(asyncError).zip(Future(ps.waitFor))
        yield (success, error, exitCode)

        d.onComplete:
          case scala.util.Success(a) => println(a)
          case scala.util.Failure(t) => println(t)

        Behaviors.stopped

