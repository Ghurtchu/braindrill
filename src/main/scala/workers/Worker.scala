package workers

import workers.children.FileHandler.In.PrepareFile
import Worker.In
import Worker.ExecutionSucceeded
import org.apache.pekko.actor.typed.receptionist.{Receptionist, ServiceKey}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import serialization.CborSerializable
import workers.children.FileHandler

import java.io.File
import scala.util.*

// Worker actor that initiates the code execution task
object Worker:

  // service key for Receptionist events
  val WorkerServiceKey = ServiceKey[Worker.StartExecution]("Worker")

  // incoming messages
  sealed trait In
  // received from LoadBalancer to start task
  case class StartExecution(code: String, language: String, replyTo: ActorRef[ExecutionResult]) extends In with CborSerializable

  // incoming messages received from CodeExecutor
  sealed trait ExecutionResult extends In {
    def value: String
  }
  // success
  case class ExecutionSucceeded(value: String) extends ExecutionResult with CborSerializable
  // failed
  case class ExecutionFailed(value: String) extends ExecutionResult with CborSerializable

  // private data model for grouping execution inputs for docker process
  private case class ExecutionInputs(compiler: String, extension: String)

  // mapping programming language to its inputs
  private val mappings: Map[String, ExecutionInputs] =
    Map(
      "python" -> ExecutionInputs("python", ".py"),
      "javascript" -> ExecutionInputs("node", ".js")
    )

  def apply(requester: Option[ActorRef[ExecutionResult]] = None): Behavior[In] =
    Behaviors.setup[In]: ctx =>
      val selfName = ctx.self.path.name

      ctx.log.info("registering myself: {} with Receptionist", selfName)
      // register to Receptionist so that LoadBalancer is updated with new worker references
      ctx.system.receptionist ! Receptionist.Register(WorkerServiceKey, ctx.self)

      Behaviors.receiveMessage[In]:
          // if asked to start execution
        case msg @ StartExecution(code, lang, replyTo) =>
          ctx.log.info(s"{} processing StartExecution", selfName)
          // try reading inputs for programming language
          mappings.get(lang) match
            // if we have inputs
            case Some(inputs) =>
              // create file handler actor which prepares the file to be executed later
              val fileHandler = ctx.spawn(FileHandler(), s"file-handler")
              ctx.log.info(s"{} sending PrepareFile to {}", selfName, fileHandler.path.name)
              
              // send PrepareFile message
              fileHandler ! FileHandler.In.PrepareFile(
                name = s"$lang${Random.nextLong}${inputs.extension}", // random number for avoiding file overwrite/shadowing
                compiler = inputs.compiler,
                code = code,
                replyTo = ctx.self
              )
              // if there is no mappings
            case None =>
              // it means programming language is unsupported
              val reason = s"unsupported language: $lang"
              ctx.log.warn(reason)

              // send back ExecutionFailed with reason
              replyTo ! ExecutionFailed(reason)

          apply(requester = Some(replyTo))

          // forward success outcome to LoadBalancer
        case msg @ ExecutionSucceeded(result) =>
          requester match
            case Some(requester) =>
              ctx.log.info(s"{} sending ExecutionSucceeded to {}", selfName, requester.path.name)
              requester ! msg
            case None =>
              ctx.log.warn(s"nobody to reply ExecutionSucceeded to, original requester is empty")

          apply(requester = None)

        // forward failed outcome to LoadBalancer
        case msg @ ExecutionFailed(reason) =>
          requester match
            case Some(requester) =>
              ctx.log.info(s"{} sending ExecutionFailed to {}", selfName, requester.path.name)
              requester ! msg
            case None =>
              ctx.log.warn(s"nobody to reply ExecutionFailed to, original requester is empty")

          apply(requester = None)

