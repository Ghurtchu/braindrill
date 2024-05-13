import cluster.ClusterBootstrap
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem

object Main:
  def main(args: Array[String]): Unit =
    Iterator
      .iterate(17356)(_ + 1)
      .take(3)
      .foreach:
        deploy("backend", _)

    deploy("load-balancer", 0)

  private def deploy(role: String, port: Int): Unit =
    // Override the configuration of the port and role
    val config = ConfigFactory
      .parseString(
        s"""
          pekko.remote.artery.canonical.port=$port
          pekko.cluster.roles = [$role]

          """)
      .withFallback(ConfigFactory.load("transformation"))


    ActorSystem[Nothing](ClusterBootstrap(), "ClusterSystem", config)
