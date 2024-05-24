
import cluster.ClusterBootstrap
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem

object BrainDrill:

  def main(args: Array[String]): Unit =
    // 3 workers nodes
    Iterator
      .iterate(17356)(_ + 1)
      .take(3)
      .foreach:
        deploy("worker", _)

    // single master node
    deploy("master", 0)

  private def deploy(role: String, port: Int): Unit =
    val cfg = ConfigFactory
      .parseString(
        s"""
          pekko.remote.artery.canonical.port=$port
          pekko.cluster.roles = [$role]

          """)
      .withFallback(ConfigFactory.load("transformation"))

    ActorSystem[Nothing](ClusterBootstrap(), "ClusterSystem", cfg)
