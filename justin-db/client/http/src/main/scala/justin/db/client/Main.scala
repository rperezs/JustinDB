package justin.db.client

import akka.actor.{ActorSystem, Cancellable}
import akka.cluster.Cluster
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteConcatenation
import akka.http.scaladsl.server.directives.DebuggingDirectives._
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import justin.consistent_hashing.{NodeId, Ring}
import justin.consul._
import justin.db.replication.N
import justin.db.storage.InMemStorage
import justin.db.{StorageNodeActor, StorageNodeActorRef}

import scala.concurrent.duration._

object Main extends App with RouteConcatenation {
  val config = ConfigFactory.parseString(s"akka.cluster.roles = [${StorageNodeActor.role}]")
    .withFallback(ConfigFactory.load())

  implicit val system       = ActorSystem("justin-db-cluster-system", config)
  implicit val executor     = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val logger = Logging(system, getClass)

  logger.info("JustinDB " + BuildInfo.version)
  logger.info("Build Info: ")
  logger.info(BuildInfo.toString)

  // SEEDS discovery
  val cluster = Cluster(system)
  val isConsulEnabled = config.getBoolean("consul.enabled")

  // retrying cluster join until success
  val scheduler: Cancellable = system.scheduler.schedule(10 seconds, 30 seconds, new Runnable {
    override def run(): Unit = {
      val selfAddress = cluster.selfAddress
      logger.debug(s"Cluster bootstrap, self address: $selfAddress")

      val serviceSeeds = if (isConsulEnabled) {
        val consulClient = new ConsulClient(ConsulClientConfig(
          host = ConsulHost(config.getString("consul.host")),
          port = ConsulPort(config.getInt("consul.port")),
          serviceName = ConsulServiceName(config.getString("consul.serviceName"))
        ))
        val serviceAddresses = consulClient.getServiceAddresses
        logger.debug(s"Cluster bootstrap, service addresses: $serviceAddresses")

        // http://doc.akka.io/docs/akka/2.4.4/scala/cluster-usage.html
        //
        // When using joinSeedNodes you should not include the node itself except for the node
        // that is supposed to be the first seed node, and that should be placed first
        // in parameter to joinSeedNodes.
        serviceAddresses filter { address =>
          address != selfAddress || address == serviceAddresses.head
        }
      } else {
        List(selfAddress)
      }

      logger.debug(s"Cluster bootstrap, found service seeds: $serviceSeeds")

      cluster.joinSeedNodes(serviceSeeds)
    }
  })

  cluster registerOnMemberUp {
    logger.info("Cluster is ready!")

    scheduler.cancel()

    val storageNodeActorRef = StorageNodeActorRef {
      val nodeId      = NodeId(config.getInt("node.id"))
      val ring        = Ring(config.getInt("ring.cluster-nodes-size"), config.getInt("ring.creation-size"))
      val storage     = new InMemStorage()
      val replication = N(config.getInt("justin-db.replication.N"))

      system.actorOf(
        props = StorageNodeActor.props(nodeId, storage, ring, replication),
        name  = StorageNodeActor.name(nodeId)
      )
    }

    // API init
    val routes = logRequestResult(system.name) {
      new HttpRouter(new ActorRefStorageNodeClient(storageNodeActorRef)).routes ~
        new HealthCheckRouter().routes ~
        new BuildInfoRouter().routes
    }

    Http()
      .bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
      .map { binding => logger.info(s"HTTP server started at ${binding.localAddress}") }
      .recover { case ex => logger.error(ex, "Could not start HTTP server") }
  }
}
