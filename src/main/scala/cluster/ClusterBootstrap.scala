package cluster

import workers.Worker
import org.apache.pekko
import org.apache.pekko.actor.typed.receptionist.Receptionist
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
import workers.Worker.*

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*
import scala.util.*

object ClusterBootstrap:

  // Behavior[Nothing] aka root behavior since it just starts nodes
  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing]: ctx =>
    val cluster = Cluster(ctx.system)
    val node = cluster.selfMember
    val cfg = ctx.system.settings.config

    if node hasRole "worker" then
      val numberOfWorkers = Try(cfg.getInt("transformation.workers-per-node")).getOrElse(50)

      // actor that sends StartExecution message to local Worker actors in a round robin fashion
      val workerRouter = ctx.spawn(
        behavior = Routers.pool(numberOfWorkers) {
          Behaviors.supervise(Worker().narrow[StartExecution])
            .onFailure(SupervisorStrategy.restart)
        } .withRoundRobinRouting(),
        name = "worker-router"
      )

      // actors are registered to the ActorSystem receptionist using a special ServiceKey.
      // All remote worker-routers will be registered to ClusterBootstrap actor system receptionist.
      // When the "worker" node starts it registers the local worker-router to the Receptionist which is cluster-wide
      // As a result "master" node can have access to remote worker-router and receive any updates about workers through worker-router
      ctx.system.receptionist ! Receptionist.Register(Worker.WorkerRouterKey, workerRouter)

    if node hasRole "master" then
      given system: ActorSystem[Nothing] = ctx.system
      given ec: ExecutionContextExecutor = ctx.executionContext
      given timeout: Timeout = Timeout(4.seconds)

      val numberOfLoadBalancers = Try(cfg.getInt("transformation.load-balancer")).getOrElse(2)
      // pool of load balancers that forward StartExecution message to the remote worker-router actors in a round robin fashion
      val loadBalancers = (1 to numberOfLoadBalancers).map: n =>
        ctx.spawn(
          behavior = Routers.group(Worker.WorkerRouterKey).withRoundRobinRouting(),
          name = s"load-balancer-$n"
        )

      val route =
        pathPrefix("lang" / Segment): lang =>
          post:
            entity(as[String]): code =>
              val loadBalancer = Random.shuffle(loadBalancers).head
              val asyncResponse = loadBalancer
                .ask[ExecutionResult](StartExecution(code, lang, _))
                .map(_.value)
                .recover(_ => "something went wrong") // TODO: make better recovery

              complete(asyncResponse)

      val (host, port) = ("0.0.0.0", 8080) // TODO: make them configurable

      Http()
        .newServerAt(host, port)
        .bind(route)

    Behaviors.empty[Nothing]