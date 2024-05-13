import cluster.ClusterBootstrap
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem

object Main:
  def main(args: Array[String]): Unit =
    // deploy 3 workers nodes
    Iterator
      .iterate(17356)(_ + 1)
      .take(3)
      .foreach:
        deploy("worker", _)

    // deploy single load-balancer
    deploy("load-balancer", 0)

  private def deploy(role: String, port: Int): Unit =
    // load config with default fallback
    val config = ConfigFactory
      .parseString(
        s"""
          pekko.remote.artery.canonical.port=$port
          pekko.cluster.roles = [$role]

          """)
      .withFallback(ConfigFactory.load("transformation"))

    // create actor system with cluster bootstrap
    ActorSystem[Nothing](ClusterBootstrap(), "ClusterSystem", config)
