package workers

import workers.children.FileHandler.In.PrepareFile
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import serialization.CborSerializable
import workers.children.FileHandler

import scala.util.*

// Worker actor that initiates the code execution task
object Worker:

  // incoming messages
  sealed trait In
  // received from LoadBalancer to initiate task
  final case class StartExecution(code: String, language: String, replyTo: ActorRef[Worker.ExecutionResult]) extends In with CborSerializable

  // incoming messages received from CodeExecutor
  sealed trait ExecutionResult extends In {
    def value: String
  }

  final case class ExecutionSucceeded(value: String) extends ExecutionResult with CborSerializable
  final case class ExecutionFailed(value: String)    extends ExecutionResult with CborSerializable

  // private data model for grouping execution inputs for docker process
  private final case class ExecutionInputs(compiler: String, extension: String)

  // mapping programming language to its inputs
  private val mappings: Map[String, ExecutionInputs] =
    Map(
      "python" -> ExecutionInputs("python", ".py"),
      "javascript" -> ExecutionInputs("node", ".js")
    )

  def apply(requester: Option[ActorRef[Worker.ExecutionResult]] = None): Behavior[In] =
    Behaviors.setup[In]: ctx =>
      val selfName = ctx.self.path.name

      Behaviors.receiveMessage[In]:
        case msg @ StartExecution(code, lang, replyTo) =>
          ctx.log.info(s"{} processing StartExecution", selfName)
          mappings.get(lang) match
            case Some(inputs) =>
              // create file handler actor which prepares the file to be executed later
              val fileHandler = ctx.spawn(FileHandler(), s"file-handler")
              ctx.log.info(s"{} sending PrepareFile to {}", selfName, fileHandler.path.name)

              fileHandler ! FileHandler.In.PrepareFile(
                name = s"$lang${Random.nextLong}${inputs.extension}", // random number for avoiding file overwrite/shadowing
                compiler = inputs.compiler,
                code = code,
                replyTo = ctx.self
              )
            case None =>
              val reason = s"unsupported language: $lang"
              ctx.log.warn(s"{} failed execution due to: {}", selfName, reason)

              replyTo ! Worker.ExecutionFailed(reason)

          // register original requester
          apply(requester = Some(replyTo))

        // forward successful outcome to LoadBalancer
        case msg @ ExecutionSucceeded(result) =>
          requester match
            case Some(requester) =>
              ctx.log.info(s"{} sending ExecutionSucceeded to {}", selfName, requester.path.name)
              requester ! msg
            case None =>
              ctx.log.warn(s"there is nobody to reply ExecutionSucceeded to, original requester is empty")

          apply(requester = None)

        // forward failed outcome to LoadBalancer
        case msg @ ExecutionFailed(reason) =>
          requester match
            case Some(requester) =>
              ctx.log.info(s"{} sending ExecutionFailed to {}", selfName, requester.path.name)
              requester ! msg
            case None =>
              ctx.log.warn(s"there is nobody to reply ExecutionFailed to, original requester is empty")

          apply(requester = None)

