import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

import better.files.File
import cats.effect.Effect
import com.avast.metrics.scalaapi.Monitor
import com.google.inject.TypeLiteral
import com.typesafe.scalalogging.StrictLogging
import io.sentry.Sentry
import lib.App._
import lib.AppException.MultipleFailuresException
import lib._
import lib.db._
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
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, TimeoutException}
import scala.util.control.NonFatal

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
        sentry.setServerName(appUUID.toString)
        sentry.setDist(if (SystemUtils.IS_OS_WINDOWS) "win" else "linux")
      } else {
        logger.info("Sentry NOT enabled")
      }

    case None => logger.info("Sentry NOT configured")
  }

  DBs.setupAll()

  DB.autoCommit { implicit session =>
    DbScheme.create
  }

  override def configure(): Unit = {
    bindConfig(config.root(), "")(binder())

    val rootMonitor = Monitor.noOp() // TODO

    implicit val scheduler: Scheduler = Scheduler(
      executor = Executors.newScheduledThreadPool(4),
      ec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
    )

    val F = Task.catsEffect
    bind(new TypeLiteral[Effect[Task]]() {}).toInstance(F)

    val blockingScheduler = Scheduler.io()

    val filesDao = new FilesDao(blockingScheduler)
    val backupSetsDao = new BackupSetsDao(blockingScheduler)
    val settingsDao = new SettingsDao(blockingScheduler)

    bind[FilesDao].toInstance(filesDao)
    bind[BackupSetsDao].toInstance(backupSetsDao)

    // TODO when this fails, does it break the app in prod?
    // upgrade DB
    DB.autoCommit { implicit session =>
      val upgrader = new DbUpgrader(settingsDao)
      upgrader.upgrade.value.runSyncUnsafe(30.seconds) match {
        case Right(_) => // ok
        case Left(err @ MultipleFailuresException(causes)) =>
          logger.error(s"Multiple failures while upgrading the DB:\n${causes.mkString("\n")}")
          throw err
        case Left(err) =>
          throw err
      }
    }

    bind[AllowedWsApiOrigins].toInstance(AllowedWsApiOrigins(config.getStringList("allowedWsApiOrigins").asScala))

    val cloudConnector = CloudConnector.fromConfig(config.getConfig("cloudConnector"), blockingScheduler)
    val settings = new Settings(settingsDao)
    val stateManager = new StateManager(cloudConnector, filesDao, settings)

    bind[CloudConnector].toInstance(cloudConnector)
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

    backupSetsDao.resetProcessingFlags().value.runSyncUnsafe(Duration.Inf)

    // create backup set if there is none
    (for {
      bss <- backupSetsDao.listAll()
      _ <- if (bss.isEmpty) backupSetsDao.create("Default") else pureResult(())
    } yield {
      ()
    }).value.toIO.unsafeRunSync()

    // startup:

    logger.info("Starting up")

    try {
      stateManager.appInit().value.runSyncUnsafe(5.minutes) match {
        case Right(_) => settings.initializing(false)
        case Left(err) => throw err
      }
    } catch {
      case e: IOException if e.getMessage.startsWith("Failed to connect") =>
        settings.session(None).value.runSyncUnsafe(2.seconds)
        logger.warn("Could not initialize app because server is unavailable", e)
        settings.initializing(false)

      case e: TimeoutException =>
        logger.warn("Could not initialize app because server is unavailable", e)
        settings.initializing(false)

      case NonFatal(e) =>
        logger.error("Could not initialize app", e)
        throw e
    }
  }

  private def bindServiceUpdater(): Unit = {
    val updater = if (SystemUtils.IS_OS_WINDOWS) {
      new WindowsServiceUpdaterExecutor
    } else new LinuxServiceUpdaterExecutor

    bind[ServiceUpdaterExecutor].toInstance(updater)
  }

  private def appUUID: UUID = {
    val f = File("uuid")
    if (f.exists) {
      UUID.fromString(f.bufferedReader.apply(_.readLine()))
    } else {
      val uuid = UUID.randomUUID()
      f.write(uuid.toString)
      logger.info(s"Generating app UUID: $uuid")
      uuid
    }
  }
}
