import actors.BrainDrill
import actors.BrainDrill.{In, TaskResult}
import actors.BrainDrill.AssignTask
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.cluster.typed.Cluster
import pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.util.Timeout
import pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko
import org.apache.pekko.actor.Scheduler
import pekko.actor.typed.*
import pekko.actor.typed.scaladsl.*
import pekko.cluster.ClusterEvent.*
import pekko.cluster.MemberStatus
import pekko.cluster.typed.*

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

object Main:

  object Frontend {

    enum Event {
      case BrainDrillsUpdated(newDrills: Set[ActorRef[BrainDrill.AssignTask]])
      case AssignTask(code: String, language: String, replyTo: ActorRef[FinalOutput])
      case TaskSucceeded(output: String)
      case TaskFailed(why: String)
    }
    
    case class FinalOutput(output: String)

    def apply(): Behavior[Event] = Behaviors.setup { ctx =>

      val adapter = ctx.messageAdapter[Receptionist.Listing] {
        case BrainDrill.WorkerServiceKey.Listing(workers) =>
          Event.BrainDrillsUpdated(workers)
      }

      ctx.system.receptionist ! Receptionist.Subscribe(BrainDrill.WorkerServiceKey, adapter)

      running(ctx, Seq.empty)
    }

    private def running(ctx: ActorContext[Event], workers: Seq[ActorRef[BrainDrill.AssignTask]], replyTo: Option[ActorRef[FinalOutput]] = None): Behavior[Event] = {

      Behaviors.receiveMessage[Event] {

        case Event.BrainDrillsUpdated(newWorkers) =>
          ctx.log.info("List of services registered with the receptionist changed: {}", newWorkers)

          running(ctx, newWorkers.toSeq)

        case Event.AssignTask(code, lang, replyTo) =>
          given timeout: Timeout = Timeout(5.seconds)
          val worker: ActorRef[AssignTask] = workers.head
          ctx.log.info("sending work for processing to {}", worker)
          ctx.ask[AssignTask, TaskResult](worker, AssignTask(code, lang, _)) {
            case Success(res) => Event.TaskSucceeded(res.output)
            case Failure(exception) => Event.TaskFailed(exception.toString)
          }
          running(ctx, workers, Some(replyTo))

        case Event.TaskSucceeded(output) =>
          ctx.log.info("Got output: {}", output)
          replyTo.foreach(_ ! FinalOutput(output))
          Behaviors.same

        case Event.TaskFailed(why) =>
          ctx.log.info("Failed due to: {}", why)
          replyTo.foreach(_ ! FinalOutput(why))
          Behaviors.same
      }
    }



  }

  object RootBehaviour {
    def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>
      val cluster = Cluster(ctx.system)
      val node = cluster.selfMember

      if (node.hasRole("backend")) {
        val workersPerNode = ctx.system.settings.config.getInt("transformation.workers-per-node")
        (1 to 10).foreach: n =>
          ctx.spawn(BrainDrill(), s"BrainDrill$n")
      }

      if (node.hasRole("frontend")) {
        
        given system: ActorSystem[Nothing] = ctx.system
        
        val frontend = ctx.spawn(Frontend(), "Frontend")
        
        given timeout: Timeout = Timeout(5.seconds)

        given ec: ExecutionContextExecutor = ctx.executionContext

        val route =
          pathPrefix("lang" / Segment): lang =>
            post:
              entity(as[String]): code =>
                val asyncExecutionResponse = frontend
                  .ask[Frontend.FinalOutput](Frontend.Event.AssignTask(code, lang, _))
                  .map(_.output)
                  .recover(_ => "something went wrong") // TODO: make better recovery

                complete(asyncExecutionResponse)

        Http()
          .newServerAt("localhost", 9000)
          .bind(route)
      }
      
      Behaviors.empty[Nothing]
    }
  }
  def main(args: Array[String]): Unit = {
    // starting 3 backends
    startup("backend", 17356)
    startup("backend", 17357)
    startup("backend", 17358)

    // starting 1 frontend
    startup("frontend", 0)
  }

  def startup(role: String, port: Int): Unit = {
    // Override the configuration of the port and role
    val config = ConfigFactory
      .parseString(
        s"""
          pekko.remote.artery.canonical.port=$port
          pekko.cluster.roles = [$role]
          """)
      .withFallback(ConfigFactory.load("transformation"))
    

    ActorSystem[Nothing](RootBehaviour(), "ClusterSystem", config)
  }


  def http(): Unit = {
    given frontend: ActorSystem[Frontend.Event] = ActorSystem(Frontend(), "frontend")
    given timeout: Timeout = Timeout(5.seconds)
    given ec: ExecutionContextExecutor = frontend.executionContext

    val route =
      pathPrefix("lang" / Segment): lang =>
        post:
          entity(as[String]): code =>
            val asyncExecutionResponse = frontend
              .ask[Frontend.FinalOutput](Frontend.Event.AssignTask(code, lang, _))
              .map(_.output)
              .recover(_ => "something went wrong") // TODO: make better recovery

            complete(asyncExecutionResponse)

    Http()
      .newServerAt("localhost", 9000)
      .bind(route)
      .foreach(_ => frontend.log.info(s"Server now online"))
  }
