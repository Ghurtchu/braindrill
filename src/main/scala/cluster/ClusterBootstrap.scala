package cluster

import workers.Worker
import workers.Worker.In
// import loadbalancer.LoadBalancer
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
import scala.util.Random.shuffle
import scala.util.*

object ClusterBootstrap:
  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing]: ctx =>
    val cluster = Cluster(ctx.system)
    val node = cluster.selfMember
    val cfg = ctx.system.settings.config

    if node hasRole "worker" then
      // on every compute node there is one service instance that delegates to N local workers
      val numberOfWorkers = Try(cfg.getInt("transformation.workers-per-node")).getOrElse(10)
      val workers: ActorRef[Worker.StartExecution] = ctx.spawn(
        Routers.pool(numberOfWorkers)(Worker().narrow[Worker.StartExecution]).withRoundRobinRouting(),
        "WorkerRouter"
      )

      val svc = ctx.spawn(Service(workers), "Service")

      ctx.system.receptionist ! Receptionist.Register(Service.Key, svc)

      // on every compute node there is one service instance that delegates to N local workers

    if node hasRole "master" then
      given system: ActorSystem[Nothing] = ctx.system
      given timeout: Timeout = Timeout(3.seconds)
      given ec: ExecutionContextExecutor = ctx.executionContext

      val serviceRouter =
        ctx.spawn(Routers.group(Service.Key), "ServiceRouter")

      val client: ActorRef[Client.In] = ctx.spawn(Client(serviceRouter), "Client")

      // ignore
//      val loadBalancerAmount = Try(cfg.getInt("transformation.load-balancer")).getOrElse(2)
//      val loadBalancers = (1 to loadBalancerAmount).map: n =>
//        ctx.spawn(LoadBalancer(), s"LoadBalancer-$n")

      val route =
        pathPrefix("lang" / Segment): lang =>
          post:
            entity(as[String]): code =>
              // val loadBalancer = shuffle(loadBalancers).head
              val asyncExecutionResponse = client
                .ask[Service.TaskResult](Client.In.AssignTask(code, lang, _))
                .map(_.output)
                .recover(_ => "something went wrong") // TODO: make better recovery

              complete(asyncExecutionResponse)

      val (host, port) = ("0.0.0.0", 8080) // TODO: make them configurable

      Http()
        .newServerAt(host, port)
        .bind(route)

    Behaviors.empty[Nothing]