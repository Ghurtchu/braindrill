package cluster

import workers.BrainDrill
import workers.BrainDrill.{In, TaskResult}
import workers.BrainDrill.AssignTask
import com.typesafe.config.ConfigFactory
import loadbalancer.LoadBalancer
import org.apache.pekko
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.Cluster
import pekko.actor.typed.{ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.util.Timeout
import pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import pekko.actor.typed.scaladsl.AskPattern.Askable
import pekko.actor.typed.*
import pekko.actor.typed.scaladsl.*

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

object ClusterBootstrap {
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
    }

    Behaviors.empty[Nothing]
  }
}