package lib

import cats.Traverse
import cats.instances.list._
import com.typesafe.scalalogging.StrictLogging
import io.sentry.Sentry
import lib.App._
import lib.client.clientapi.ClientStatus
import lib.db.FilesDao
import lib.server.CloudConnector
import lib.server.serverapi.ListFilesResponse
import lib.settings.Settings

class StateManager(cloudConnector: CloudConnector, dao: FilesDao, settings: Settings) extends StrictLogging {
  def appInit(): Result[Unit] = {
    settings.session.flatMap {
      case Some(sessionId) => downloadRemoteFilesList(sessionId)
      case None => pureResult(())
    }
  }

  def login(implicit session: ServerSession): Result[Unit] = {
    for {
      _ <- settings.session(Option(session))
      _ <- downloadRemoteFilesList
    } yield {
      val sc = Sentry.getStoredClient
      sc.addExtra("deviceId", session.deviceId.value)
      ()
    }
  }

  def status: Result[ClientStatus] = {
    if (settings.initializing) {
      pureResult(ClientStatus.Initializing)
    } else {
      for {
        session <- settings.session
        status <- session match {
          case Some(ss) =>
            cloudConnector
              .status(ss)
              .map[ClientStatus] { _ =>
                logger.debug("Status READY")
                ClientStatus.Ready(ss.rootUri, ss.serverVersion, ss.deviceId)
              }
              .recover {
                case e =>
                  logger.debug("Server not available - status DISCONNECTED", e)
                  ClientStatus.Disconnected
              }

          case None =>
            logger.debug("Session ID not available - status INSTALLED")
            pureResult(ClientStatus.Installed)
        }
      } yield status
    }
  }

  def downloadRemoteFilesList(implicit session: ServerSession): Result[Unit] = {
    logger.info("Downloading remote files list")

    for {
      allFiles <- cloudConnector.listFiles(Some(session.deviceId)).subflatMap {
        case ListFilesResponse.FilesList(files) =>
          logger.debug(s"Downloaded files list with ${files.length} items")
          Right(files)
        case ListFilesResponse.DeviceNotFound(_) =>
          logger.error("Server does not know this device even though the request was authenticated")
          Left(AppException.Unauthorized)
      }
      _ <- dao.deleteAll()
      _ <- Traverse[List].sequence(allFiles.map(dao.saveRemoteFile).toList)
    } yield {
      logger.info(s"Downloaded ${allFiles.length} remote files")
      ()
    }
  }
}
