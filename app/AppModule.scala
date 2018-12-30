import java.util.concurrent.Executors

import cats.effect.Effect
import com.avast.metrics.scalaapi.Monitor
import com.google.inject.TypeLiteral
import com.typesafe.scalalogging.StrictLogging
import io.sentry.Sentry
import lib.App._
import lib._
import lib.db.{Dao, DbScheme, DbUpgrader}
import lib.server.CloudConnector
import lib.settings.Settings
import monix.eval.Task
import monix.execution.Scheduler
import net.codingwell.scalaguice.ScalaModule
import org.apache.commons.lang3.SystemUtils
import org.http4s.Uri
import org.http4s.client.blaze.Http1Client
import org.http4s.client.middleware.FollowRedirect
import play.api.{Configuration, Environment}
import scalikejdbc._
import scalikejdbc.config.DBs
import updater.{GithubConnector, LinuxServiceUpdaterExecutor, ServiceUpdaterExecutor, WindowsServiceUpdaterExecutor}
import utils.AllowedWsApiOrigins

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class AppModule(environment: Environment, configuration: Configuration)
    extends ScalaModule
    with PropertiesConfiguration
    with StrictLogging {
  private val config = configuration.underlying

  App.SentryDsn match {
    case Some(dsn) =>
      if (config.getBoolean("sentry.enabled") && config.getString("environment") != "dev") {
        logger.info("Sentry configured")
        val sentry = Sentry.init(dsn)
        sentry.setRelease(App.versionStr)
        sentry.addTag("app", "client")
        sentry.setEnvironment(config.getString("environment"))
        sentry.setServerName(config.getString("deviceId"))
        sentry.setDist(if (SystemUtils.IS_OS_WINDOWS) "win" else "linux")
      } else {
        logger.info("Sentry NOT enabled")
      }

    case None => logger.info("Sentry NOT configured")
  }

  DBs.setupAll()

  DB.autoCommit { implicit session =>
    DbScheme.create
    DbUpgrader.upgrade
  }

  override def configure(): Unit = {
    bindConfig(config.root(), "")(binder())

    implicit val scheduler: Scheduler = Scheduler(
      executor = Executors.newScheduledThreadPool(4),
      ec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
    )

    val F = Task.catsEffect
    bind(new TypeLiteral[Effect[Task]]() {}).toInstance(F)

    val blockingScheduler = Scheduler.io()

    val rootMonitor = Monitor.noOp() // TODO

    bind[AllowedWsApiOrigins].toInstance(AllowedWsApiOrigins(config.getStringList("allowedWsApiOrigins").asScala))

    val deviceId = DeviceId(config.getString("deviceId"))
    bind[DeviceId].toInstance(deviceId)

    val cloudConnector = CloudConnector.fromConfig(config.getConfig("cloudConnector"), blockingScheduler)
    val dao = new Dao(blockingScheduler)
    val settings = new Settings(dao)
    val stateManager = new StateManager(deviceId, cloudConnector, dao, settings)

    bind[CloudConnector].toInstance(cloudConnector)
    bind[Dao].toInstance(dao)
    bind[Settings].toInstance(settings)
    bind[StateManager].toInstance(stateManager)

    bind[GithubConnector].toInstance {
      new GithubConnector(
        FollowRedirect(5)(Http1Client[Task]().runSyncUnsafe(Duration.Inf)),
        Uri.unsafeFromString(config.getString("updater.releasesUrl")),
        App.version,
        blockingScheduler
      )
    }

    bind[FiniteDuration].annotatedWithName("updaterCheckPeriod").toInstance(config.getDuration("updater.checkPeriod").toMillis.millis)

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
      new WindowsServiceUpdaterExecutor
    } else new LinuxServiceUpdaterExecutor

    bind[ServiceUpdaterExecutor].toInstance(updater)
  }
}
