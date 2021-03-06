import better.files.File
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import lib.server.CloudConnector
import lib.server.serverapi._
import lib.{DeviceId, ServerSession}
import monix.execution.Scheduler.Implicits.global
import org.http4s.Uri
import org.scalatest.FunSuite
import utils.Sha256
import utils.TestOps._

class IntegrationTest extends FunSuite with StrictLogging {
  private val rbackupIp: String = {
    val ip = Option(System.getenv("RBACKUP_IP")).getOrElse("localhost")

    if (ip == "0.0.0.0") "localhost" else ip
  }

  private val connector = CloudConnector.fromConfig(ConfigFactory.empty(), global)
  private val rootUri = Uri.unsafeFromString(s"http://$rbackupIp:3369")

  logger.info(s"Using root URI $rootUri")

  test("read status") {
    assertResult("RBackup running")(connector.status(rootUri).unwrappedFutureValue.status)
  }

  test("register account and login") {
    val username = randomString(10)

    val RegistrationResponse.Created(accountId) = connector.registerAccount(rootUri, username, "password").unwrappedFutureValue

    assert(accountId.nonEmpty)

    val LoginResponse.SessionCreated(session) = connector
      .login(
        rootUri = rootUri,
        deviceId = DeviceId("rbackup-test"),
        username = username,
        password = "password"
      )
      .unwrappedFutureValue

    assertResult(rootUri)(session.rootUri)
  }

  test("upload, list and download") {
    val theFile = File(getClass.getClassLoader.getResource("theFileToBeUploaded.dat"))
    val theFileHash = Sha256(theFile.sha256)
    val theFile2 = File.newTemporaryFile("rbackup")
    theFile2.write(randomString(1000))

    // login
    val username = randomString(10)
    connector.registerAccount(rootUri, username, "password").unwrappedFutureValue

    val LoginResponse.SessionCreated(session) = connector
      .login(
        rootUri = rootUri,
        deviceId = DeviceId("rbackup-test"),
        username = username,
        password = "password"
      )
      .unwrappedFutureValue

    implicit val s: ServerSession = session

    // list files

    val ListFilesResponse.FilesList(emptyFiles) = connector.listFiles(None).unwrappedFutureValue
    assertResult(Seq.empty)(emptyFiles)

    // upload

    val UploadResponse.Uploaded(remoteFile1) = connector.upload(theFile)((_, _, _) => ()).unwrappedFutureValue

    assertResult(theFile.pathAsString)(remoteFile1.originalName)
    assertResult("rbackup-test")(remoteFile1.deviceId)

    val List(version1) = remoteFile1.versions.toList

    assertResult(1520)(version1.size)
    assertResult(Sha256(theFile.sha256))(version1.hash)
    assertResult(theFile.lastModifiedTime)(version1.mtime.toInstant)

    // list files again - now contains the file

    val ListFilesResponse.FilesList(files1) = connector.listFiles(None).unwrappedFutureValue
    assertResult(Seq(remoteFile1))(files1)
    assertResult(theFile.pathAsString)(files1.head.originalName)
    assertResult(theFile.lastModifiedTime)(files1.head.versions.head.mtime.toInstant)

    // upload again

    val UploadResponse.Uploaded(remoteFile2) = connector.upload(theFile)((_, _, _) => ()).unwrappedFutureValue

    assertResult(theFile.pathAsString)(remoteFile2.originalName)
    assertResult("rbackup-test")(remoteFile2.deviceId)

    assertResult(2)(remoteFile2.versions.size)

    val Some(version2) = remoteFile2.versions.tail.headOption

    assertResult(1520)(version2.size)
    assertResult(theFileHash)(version1.hash)

    // list files again - now contains two revisions of the file

    val ListFilesResponse.FilesList(files2) = connector.listFiles(None).unwrappedFutureValue
    assertResult(1)(files2.size)
    assertResult(Seq(remoteFile2))(files2)

    // download the first version

    val dest = File.newTemporaryFile(prefix = "rbackup-test_")
    val DownloadResponse.Downloaded(finalDestFile, remVer) = connector.download(version1, dest)((_, _, _) => ()).unwrappedFutureValue

    assertResult(dest)(finalDestFile)
    assertResult(version1)(remVer)
    assertResult(dest.sha256)(theFile.sha256)

    // upload second file

    for (_ <- 1 to 10) {
      val UploadResponse.Uploaded(remoteFile) = connector.upload(theFile2)((_, _, _) => ()).unwrappedFutureValue
      assertResult(theFile2.pathAsString)(remoteFile.originalName)
    }

    // list files again - now contains two files

    val ListFilesResponse.FilesList(files3) = connector.listFiles(None).unwrappedFutureValue
    assertResult(2)(files3.size)
    val Seq(remoteFile3, remoteFile4) = files3.sortBy(_.originalName) // must be sorted to be deterministic

    assertResult(remoteFile2)(remoteFile3)
    assertResult(10)(remoteFile4.versions.size)
    assertResult(theFile2.pathAsString)(remoteFile4.originalName)
  }

  test("remove file version") {
    val theFile = File(getClass.getClassLoader.getResource("theFileToBeUploaded.dat"))
    val theFile2 = File.newTemporaryFile("rbackup")
    theFile2.write(randomString(1000))

    // login
    val username = randomString(10)
    connector.registerAccount(rootUri, username, "password").unwrappedFutureValue

    val LoginResponse.SessionCreated(session) = connector
      .login(
        rootUri = rootUri,
        deviceId = DeviceId("rbackup-test"),
        username = username,
        password = "password"
      )
      .unwrappedFutureValue

    implicit val s: ServerSession = session

    // upload 2 files

    for (_ <- 1 to 10) {
      val UploadResponse.Uploaded(remoteFile) = connector.upload(theFile)((_, _, _) => ()).unwrappedFutureValue
      assertResult(theFile.pathAsString)(remoteFile.originalName)
    }

    for (_ <- 1 to 5) {
      val UploadResponse.Uploaded(remoteFile) = connector.upload(theFile2)((_, _, _) => ()).unwrappedFutureValue
      assertResult(theFile2.pathAsString)(remoteFile.originalName)
    }

    // list files

    val ListFilesResponse.FilesList(files1) = connector.listFiles(None).unwrappedFutureValue
    val Seq(remoteFile1, remoteFile2) = files1.sortBy(_.originalName)

    assertResult(10)(remoteFile1.versions.size)
    assertResult(5)(remoteFile2.versions.size)

    // remove file version

    assertResult(RemoveFileVersionResponse.Success)(connector.removeFileVersion(remoteFile1.versions(0).version).unwrappedFutureValue)
    assertResult(RemoveFileVersionResponse.Success)(connector.removeFileVersion(remoteFile1.versions(1).version).unwrappedFutureValue)
    assertResult(RemoveFileVersionResponse.Success)(connector.removeFileVersion(remoteFile2.versions(1).version).unwrappedFutureValue)

    // list files

    val ListFilesResponse.FilesList(files2) = connector.listFiles(None).unwrappedFutureValue
    val Seq(remoteFile12, remoteFile22) = files2.sortBy(_.originalName)

    assertResult(8)(remoteFile12.versions.size)
    assertResult(4)(remoteFile22.versions.size)

  }

  test("remove file") {
    val theFile = File(getClass.getClassLoader.getResource("theFileToBeUploaded.dat"))
    val theFile2 = File.newTemporaryFile("rbackup")
    theFile2.write(randomString(1000))

    // login
    val username = randomString(10)
    connector.registerAccount(rootUri, username, "password").unwrappedFutureValue

    val LoginResponse.SessionCreated(session) = connector
      .login(
        rootUri = rootUri,
        deviceId = DeviceId("rbackup-test"),
        username = username,
        password = "password"
      )
      .unwrappedFutureValue

    implicit val s: ServerSession = session

    // upload 2 files

    for (_ <- 1 to 10) {
      val UploadResponse.Uploaded(remoteFile) = connector.upload(theFile)((_, _, _) => ()).unwrappedFutureValue
      assertResult(theFile.pathAsString)(remoteFile.originalName)
    }

    for (_ <- 1 to 5) {
      val UploadResponse.Uploaded(remoteFile) = connector.upload(theFile2)((_, _, _) => ()).unwrappedFutureValue
      assertResult(theFile2.pathAsString)(remoteFile.originalName)
    }

    // list files

    val ListFilesResponse.FilesList(files1) = connector.listFiles(None).unwrappedFutureValue
    val Seq(remoteFile1, remoteFile2) = files1.sortBy(_.originalName)

    assertResult(10)(remoteFile1.versions.size)
    assertResult(5)(remoteFile2.versions.size)

    // remove file

    assertResult(RemoveFileResponse.Success)(connector.removeFile(remoteFile1.id).unwrappedFutureValue)

    // list files

    val ListFilesResponse.FilesList(files2) = connector.listFiles(None).unwrappedFutureValue
    val Seq(remoteFile22) = files2

    assertResult(5)(remoteFile22.versions.size)

  }
}
