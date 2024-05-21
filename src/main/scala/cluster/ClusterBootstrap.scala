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

/**
 * The group routers relies on the Receptionist and will therefore route messages to services registered in any node of the cluster.
 */

/**
 * each "worker" node starts a WorkDelegator that distributes work over N local Workers.
 * The "master" node then message the WorkDelegator instances through a group router.
 * The router finds WorkDelegator-s by subscribing to the cluster receptionist and a service key - WorkDelegator.Key.
 * Each WorkDelegator is registered to the receptionist when started.
 */

object ClusterBootstrap:
  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing]: ctx =>
    val cluster = Cluster(ctx.system)
    val node = cluster.selfMember
    val cfg = ctx.system.settings.config

    if node hasRole "worker" then
      // number of workers on each node, let's say n = 50
      val numberOfWorkers = Try(cfg.getInt("transformation.workers-per-node")).getOrElse(10)
      // router pool which has access to available n workers with round robing routing
      // pool of Workers or ActorRef[Worker.StartExecution] - actor ref that handles Worker.StartExecution message
      val workersPool: ActorRef[Worker.StartExecution] = ctx.spawn( // let's say 10 workers
        behavior = Routers.pool(numberOfWorkers)(Worker().narrow[Worker.StartExecution]).withRoundRobinRouting(),
        "WorkersPool"
      )
      // on every "worker" node there is only one WorkDelegator instance that delegates to N local workers
      val workDelegator: ActorRef[WorkDelegator.In] = ctx.spawn(WorkDelegator(workersPool), "WorkDelegator")

      // ActorRefs are registered to the receptionist using a ServiceKey
      // so all WorkDelegator-s will be registered to ClusterBootstrap actor system receptionist
      // when the node starts it registers itself to Receptionist, so that "master" node can have access to remote WorkDelegator
      ctx.system.receptionist ! Receptionist.Register(WorkDelegator.Key, workDelegator)

    if node hasRole "master" then
      given system: ActorSystem[Nothing] = ctx.system
      given timeout: Timeout = Timeout(3.seconds)
      given ec: ExecutionContextExecutor = ctx.executionContext

      // ActorRef which routes AssignTask message to any WorkDelegator on any node, with round robin balancing
      // random reference of WorkDelegator - selected by round robin algorithm
      val workDelegatorPool: ActorRef[WorkDelegator.In.DelegateWork] = ctx.spawn( // let's say 3 WorkerDelegators due to 3 nodes
          Routers.group(WorkDelegator.Key).withRoundRobinRouting(),
          "WorkDelegatorPool"
        )

      val loadBalancer = ctx.spawn(LoadBalancer(workDelegatorPool), "LoadBalancer")

      val route =
        pathPrefix("lang" / Segment): lang =>
          post:
            entity(as[String]): code =>
              val asyncResponse = loadBalancer
                .ask[Worker.ExecutionResult](LoadBalancer.In.AssignTask(code, lang, _))
                .map(_.value)
                .recover(_ => "something went wrong") // TODO: make better recovery

              complete(asyncResponse)

      val (host, port) = ("0.0.0.0", 8080) // TODO: make them configurable

      Http()
        .newServerAt(host, port)
        .bind(route)

    Behaviors.empty[Nothing]