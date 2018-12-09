import java.util.concurrent.Executors

import cats.effect.Effect
import com.avast.metrics.scalaapi.Monitor
import com.google.inject.TypeLiteral
import com.typesafe.scalalogging.StrictLogging
import io.sentry.Sentry
import lib.App._
import lib._
import lib.db.{Dao, DbScheme}
import lib.server.CloudConnector
import lib.settings.Settings
import monix.eval.Task
import monix.execution.Scheduler
import net.codingwell.scalaguice.ScalaModule
import org.apache.commons.lang3.SystemUtils
import org.http4s.Uri
import org.http4s.client.blaze.Http1Client
import play.api.{Configuration, Environment}
import scalikejdbc._
import scalikejdbc.config.DBs
import updater._
import utils.AllowedWsApiOrigins

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class AppModule(environment: Environment, configuration: Configuration)
    extends ScalaModule
    with PropertiesConfiguration
    with StrictLogging {
  private val config = configuration.underlying

  if (config.getBoolean("sentry.enabled") && config.getString("sentry.environment") != "dev") {
    logger.info("Sentry configured")
    val sentry = Sentry.init(config.getString("sentry.dsn"))
    sentry.setRelease(App.versionStr)
    sentry.setEnvironment(config.getString("sentry.environment"))
    sentry.setServerName(config.getString("deviceId"))
    sentry.setDist(config.getString("sentry.dist"))
  } else {
    logger.info("Sentry NOT configured")
  }

  DBs.setupAll()

  DB.autoCommit { implicit session =>
    DbScheme.create
  }

  override def configure(): Unit = {
    bindConfig(config.root(), "")(binder())

    val executorService = Executors.newCachedThreadPool()

    implicit val scheduler: Scheduler = Scheduler(
      executor = Executors.newScheduledThreadPool(4),
      ec = ExecutionContext.fromExecutorService(Executors.newWorkStealingPool(16))
    )

    val F = Task.catsEffect
    bind(new TypeLiteral[Effect[Task]]() {}).toInstance(F)

    val blockingScheduler = Scheduler.io()

    val rootMonitor = Monitor.noOp() // TODO

    bind[AllowedWsApiOrigins].toInstance(AllowedWsApiOrigins(config.getStringList("allowedWsApiOrigins").asScala))

    val cloudConnector = CloudConnector.fromConfig(config.getConfig("cloudConnector"), blockingScheduler)
    val dao = new Dao(executorService)
    val settings = new Settings(dao)
    val stateManager = new StateManager(DeviceId(config.getString("deviceId")), cloudConnector, dao, settings)

    bind[CloudConnector].toInstance(cloudConnector)
    bind[Dao].toInstance(dao)
    bind[Settings].toInstance(settings)
    bind[StateManager].toInstance(stateManager)

    bind[GithubConnector].toInstance {
      new GithubConnector(
        Http1Client[Task]().runSyncUnsafe(Duration.Inf),
        Uri.unsafeFromString(config.getString("updater.releasesUrl")),
        App.version,
        blockingScheduler
      )
    }

    bind[Monitor].annotatedWithName("FilesHandler").toInstance(rootMonitor.named("fileshandler"))

    bind[Scheduler].toInstance(scheduler)
    bind[Scheduler].annotatedWithName("blocking").toInstance(blockingScheduler)

    bindServiceUpdater()
    bind[App].asEagerSingleton()

    dao.resetProcessingFlags().value.runSyncUnsafe(Duration.Inf)

    // create backup set if there is none
    (for {
      bss <- dao.listAllBackupSets()
      _ <- if (bss.isEmpty) dao.createBackupSet("Default") else pureResult(())
    } yield {
      ()
    }).value.toIO.unsafeRunSync()

    // startup:

    stateManager.appInit().value.toIO.unsafeRunSync() match {
      case Right(_) => settings.initializing(false)
      case Left(err) => throw err
    }
  }

  private def bindServiceUpdater(): Unit = {
    val updater = if (SystemUtils.IS_OS_WINDOWS) {
      new WindowsServiceUpdater
    } else new LinuxServiceUpdater

    bind[ServiceUpdater].toInstance(updater)
  }
}
