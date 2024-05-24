
import cluster.ClusterSystem
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.typed.ActorSystem

import scala.util.Try

object BrainDrill extends App:

  val cfg = ConfigFactory.load()
  val clusterName = Try(cfg.getString("clustering.cluster.name"))
    .getOrElse("ClusterSystem")

  ActorSystem[Nothing](
    guardianBehavior = ClusterSystem(),
    name = clusterName,
    config = cfg
  )