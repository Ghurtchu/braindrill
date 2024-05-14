package cluster

import workers.Worker
import workers.helpers.DockerImagePuller
import workers.Worker.In
import workers.Worker.StartExecution
import com.typesafe.config.{Config, ConfigFactory}
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
import scala.util._

object ClusterBootstrap:

  val LanguageToDockerImage = Map(
    "python" -> "python:3",
    "javascript" -> "node:14"
  )

  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing]: ctx =>
    // retrieve the Cluster instance
    val cluster = Cluster(ctx.system)
    // reach the node
    val node = cluster.selfMember
    // reach the config
    val config = ctx.system.settings.config

    // if it's worker node
    if node hasRole "worker" then
      // read how many worker actors should be on each worker node
      val workersPerNode = Try(config.getInt("transformation.workers-per-node")).getOrElse(10)
      // spawn all worker actors on that node
      1 to workersPerNode foreach: n =>
        ctx.spawn(Worker(), s"Worker$n")

      LanguageToDockerImage.values.foreach: image =>
        ctx.spawn(DockerImagePuller(), s"DockerImagePuller$image")

    // if it's load balancer node
    if node hasRole "load-balancer" then
      given system: ActorSystem[Nothing] = ctx.system
      given timeout: Timeout = Timeout(3.seconds)
      given ec: ExecutionContextExecutor = ctx.executionContext

      // load the config for load balancer amount, get or else 2
      val loadBalancerAmount = Try(config.getInt("transformation.load-balancer")).getOrElse(2)

      // spawn at least 2 load balancer instances
      val loadBalancers = (1 to loadBalancerAmount).map: n =>
        ctx.spawn(LoadBalancer(), s"LoadBalancer$n")

      // define http route for accepting requests
      val route =
        pathPrefix("lang" / Segment): lang =>
          post:
            entity(as[String]): code => // read code from request body
              val loadBalancer = loadBalancers(Random nextInt loadBalancerAmount)
              val asyncExecutionResponse = loadBalancer // ask load balancer
                .ask[LoadBalancer.TaskResult](LoadBalancer.In.AssignTask(code, lang, _)) // to assign task to one of the workers
                .map(_.output) // get the output
                .recover(_ => "something went wrong") // TODO: make better recovery

              complete(asyncExecutionResponse) // send back HTTP response

      val (host, port) = ("0.0.0.0", 8080) // make them configurable

      // deploy http server
      Http()
        .newServerAt(host, port)
        .bind(route)

    Behaviors.empty[Nothing]