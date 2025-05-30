import cluster.ClusterSystem
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem

object BrainDrill extends App:

  val cfg = ConfigFactory.load()
  val clusterName = scala.util.Try(cfg.getString("clustering.cluster.name"))
    .getOrElse("ClusterSystem")

  ActorSystem[Nothing](
    guardianBehavior = ClusterSystem(),
    name = clusterName,
    config = cfg
  )
