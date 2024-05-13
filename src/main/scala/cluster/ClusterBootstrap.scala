package cluster

import workers.Worker
import workers.Worker.In
import workers.Worker.StartExecution
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

object ClusterBootstrap:
  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing]: ctx =>
    // retrieve the Cluster instance
    val cluster = Cluster(ctx.system)
    // reach the node
    val node = cluster.selfMember

    // if it's worker node
    if node hasRole "worker" then
      // read how many worker actors should be on each worker node
      val workersPerNode = ctx.system.settings.config.getInt("transformation.workers-per-node")
      // spawn all worker actors on that node
      (1 to workersPerNode).foreach: n =>
        ctx.spawn(Worker(), s"Worker$n")

    // if it's load balancer node
    if node hasRole "load-balancer" then
      given system: ActorSystem[Nothing] = ctx.system
      given timeout: Timeout = Timeout(3.seconds)
      given ec: ExecutionContextExecutor = ctx.executionContext

      // spawn load balancer instance
      val loadBalancer = ctx.spawn(LoadBalancer(), "LoadBalancer")

      // define http route for accepting requests
      val route =
        pathPrefix("lang" / Segment): lang =>
          post:
            entity(as[String]): code => // read code from request body
              val asyncExecutionResponse = loadBalancer // ask load balancer
                .ask[LoadBalancer.TaskResult](LoadBalancer.In.AssignTask(code, lang, _)) // to assign task to one of the workers
                .map(_.output) // get the output
                .recover(_ => "something went wrong") // TODO: make better recovery

              complete(asyncExecutionResponse) // send back HTTP response

      val (host, port) = ("localhost", 9000) // make them configurable

      // deploy http server
      Http()
        .newServerAt(host, port)
        .bind(route)

    Behaviors.empty[Nothing]