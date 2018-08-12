package lib

import java.util.concurrent.atomic.AtomicReference

import better.files.File
import controllers.WsApiController
import io.circe.Json
import io.circe.generic.extras.auto._
import javax.inject.Inject
import lib.App._
import lib.CirceImplicits._
import lib.clientapi.FileTreeNode.Version
import lib.serverapi.{RemoteFile, RemoteFileVersion}

class CloudFilesRegistry @Inject()(wsApiController: WsApiController, dao: Dao) {

  private val lock = new Object

  private val cloudFilesList: AtomicReference[CloudFilesList] = lock.synchronized { // init
    new AtomicReference(CloudFilesList(Nil))
  }

  // TODO connect to DB

  def updateFile(remoteFile: RemoteFile): Result[CloudFilesList] = lock.synchronized {
    val newList = cloudFilesList.updateAndGet(_.update(remoteFile))

    wsApiController
      .send(
        controllers.WsMessage(
          "fileTreeUpdate",
          FileTreeUpdate(remoteFile.originalName, remoteFile.versions.map(Version(remoteFile.originalName, _))).asJson
        ))
      .map(_ => newList)
  }

  def updateFilesList(cloudFilesList: CloudFilesList): Result[CloudFilesList] = lock.synchronized {
    this.cloudFilesList.set(cloudFilesList)
    pureResult(cloudFilesList)
  }

  def versions(file: File): Option[Vector[RemoteFileVersion]] = {
    cloudFilesList.get().versions(file)
  }

  def get(file: File): Option[RemoteFile] = {
    cloudFilesList.get().get(file)
  }
}

private case class FileTreeUpdate(path: String, versions: Seq[Version]) {
  def asJson: Json = {
    parseSafe(s"""{"path": "$path", "versions": ${versions.map(_.toJson).mkString("[", ", ", "]")}}""")
  }
}
