import actors.BrainDrill
import actors.BrainDrill.{In, TaskResult}
import actors.BrainDrill.AssignTask
import com.typesafe.config.ConfigFactory
import loadbalancer.LoadBalancer
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

  object RootBehaviour {
    def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>
      val cluster = Cluster(ctx.system)
      val node = cluster.selfMember

      if (node hasRole "backend") {
        val workersPerNode = ctx.system.settings.config.getInt("transformation.workers-per-node")
        (1 to 10).foreach: n =>
          ctx.spawn(BrainDrill(), s"BrainDrill$n")
      }

      if (node hasRole "load-balancer") {
        given system: ActorSystem[Nothing] = ctx.system
        given timeout: Timeout = Timeout(5.seconds)
        given ec: ExecutionContextExecutor = ctx.executionContext

        val loadBalancer = ctx.spawn(LoadBalancer(), "LoadBalancer")

        val route =
          pathPrefix("lang" / Segment): lang =>
            post:
              entity(as[String]): code =>
                val asyncExecutionResponse = loadBalancer
                  .ask[LoadBalancer.TaskResult](LoadBalancer.In.AssignTask(code, lang, _))
                  .map(_.output)
                  .recover(_ => "something went wrong") // TODO: make better recovery

                complete(asyncExecutionResponse)

        val (host, port) = ("localhost", 9000)
        Http()
          .newServerAt(host, port)
          .bind(route)
          .foreach(_ => ctx.log.info(s"Server deployed to: $host$port"))
      }
      
      Behaviors.empty[Nothing]
    }
  }
  def main(args: Array[String]): Unit = {
    // starting 3 backends
    Iterator
      .iterate(17356)(_ + 1)
      .take(3)
      .foreach:
        startup("backend", _)
    
    // starting 1 frontend
    startup("load-balancer", 0)
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