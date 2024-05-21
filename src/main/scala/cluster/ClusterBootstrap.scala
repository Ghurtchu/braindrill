package cluster

import loadbalancer.WorkDelegator.In
import loadbalancer.{LoadBalancer, WorkDelegator}
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

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*
import scala.util.*

object ClusterBootstrap:
  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing]: ctx =>
    val cluster = Cluster(ctx.system)
    val node = cluster.selfMember
    val cfg = ctx.system.settings.config

    if node hasRole "worker" then
      // number of workers on each node, let's say n = 50
      val numberOfWorkers = Try(cfg.getInt("transformation.workers-per-node")).getOrElse(10)
      // router pool which has access to available n workers with round robing routing
      val workers: ActorRef[Worker.StartExecution] = ctx.spawn(
        behavior = Routers.pool(numberOfWorkers)(Worker().narrow[Worker.StartExecution]).withRoundRobinRouting(),
        "WorkerRouter"
      )
      // on every "worker" node there is one WorkDelegator instance that delegates to N local workers
      val workDelegator: ActorRef[WorkDelegator.In] = ctx.spawn(WorkDelegator(workers), "WorkDelegator")

      // register WorkDistributor.Key events to system receptionist
      ctx.system.receptionist ! Receptionist.Register(WorkDelegator.Key, workDelegator)

    if node hasRole "master" then
      given system: ActorSystem[Nothing] = ctx.system
      given timeout: Timeout = Timeout(3.seconds)
      given ec: ExecutionContextExecutor = ctx.executionContext

      // actor reference which routes AssignTask message to WorkDelegator
      val workDelegator: ActorRef[WorkDelegator.In.AssignTask] = ctx.spawn(
          Routers.group(WorkDelegator.Key).withRoundRobinRouting(),
          "WorkDelegatorRouter"
        )

      val loadBalancer = ctx.spawn(LoadBalancer(workDelegator), "LoadBalancer")

      val route =
        pathPrefix("lang" / Segment): lang =>
          post:
            entity(as[String]): code =>
              val asyncResponse = loadBalancer
                .ask[WorkDelegator.TaskResult](LoadBalancer.In.AssignTask(code, lang, _))
                .map(_.output)
                .recover(_ => "something went wrong") // TODO: make better recovery

              complete(asyncResponse)

      val (host, port) = ("0.0.0.0", 8080) // TODO: make them configurable

      Http()
        .newServerAt(host, port)
        .bind(route)

    Behaviors.empty[Nothing]