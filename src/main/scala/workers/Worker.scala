package workers

import org.apache.pekko.actor.typed.receptionist.ServiceKey
import workers.children.FileHandler.In.PrepareFile
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import serialization.CborSerializable
import workers.children.FileHandler

import scala.util.*

object Worker:

  val WorkerRouterKey = ServiceKey[Worker.StartExecution]("worker-router.StartExecution")

  private final case class LanguageSpecifics(
      compiler: String,
      extension: String,
      dockerImage: String
  )

  private val languageSpecifics: Map[String, LanguageSpecifics] =
    Map(
      "java" -> LanguageSpecifics(
        compiler = "java",
        extension = ".java",
        dockerImage = "openjdk:17"
      ),
      "python" -> LanguageSpecifics(
        compiler = "python3",
        extension = ".py",
        dockerImage = "python"
      ),
      "javascript" -> LanguageSpecifics(
        compiler = "node",
        extension = ".js",
        dockerImage = "node"
      )
    )

  sealed trait In
  final case class StartExecution(
      code: String,
      language: String,
      replyTo: ActorRef[Worker.ExecutionResult]
  ) extends In
      with CborSerializable

  sealed trait ExecutionResult extends In:
    def value: String

  final case class ExecutionSucceeded(value: String) extends ExecutionResult with CborSerializable
  final case class ExecutionFailed(value: String) extends ExecutionResult with CborSerializable

  def apply(workerRouter: Option[ActorRef[Worker.ExecutionResult]] = None): Behavior[In] =
    Behaviors.setup[In]: ctx =>
      val self = ctx.self

      Behaviors.receiveMessage[In]:
        case msg @ StartExecution(code, lang, replyTo) =>
          ctx.log.info(s"{} processing StartExecution", self)
          languageSpecifics get lang match
            case Some(specifics) =>
              val fileHandler = ctx.spawn(FileHandler(), s"file-handler")
              ctx.log.info(s"{} sending PrepareFile to {}", self, fileHandler)

              fileHandler ! FileHandler.In.PrepareFile(
                name =
                  s"$lang${Random.nextInt}${specifics.extension}", // random number for avoiding file overwrite/shadowing
                compiler = specifics.compiler,
                dockerImage = specifics.dockerImage,
                code = code,
                replyTo = ctx.self
              )
            case None =>
              val reason = s"unsupported language: $lang"
              ctx.log.warn(s"{} failed execution due to: {}", self, reason)

              replyTo ! Worker.ExecutionFailed(reason)

          // register original requester
          apply(workerRouter = Some(replyTo))

        case msg @ ExecutionSucceeded(result) =>
          ctx.log.info(s"{} sending ExecutionSucceeded to {}", self, workerRouter)
          workerRouter.foreach(_ ! msg)

          apply(workerRouter = None)

        case msg @ ExecutionFailed(reason) =>
          ctx.log.info(s"{} sending ExecutionFailed to {}", self, workerRouter)
          workerRouter.foreach(_ ! msg)

          apply(workerRouter = None)
