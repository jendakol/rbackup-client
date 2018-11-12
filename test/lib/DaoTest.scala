package lib

import java.time.Duration
import java.util.concurrent.Executors

import better.files.File
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{BeforeAndAfterEach, FunSuite}
import scalikejdbc.config.DBs
import scalikejdbc.{ConnectionPool, DB, _}
import utils.TestOps._

class DaoTest extends FunSuite with BeforeAndAfterEach {

  // TODO add all methods

  test("insert and list backup set") {
    val dao = new Dao(Executors.newCachedThreadPool())

    assertResult(List.empty)(dao.listAllBackupSets().unwrappedFutureValue)
    val bs = dao.createBackupSet("ahoj").unwrappedFutureValue
    assertResult("ahoj")(bs.name)
    assertResult(None)(bs.lastExecution)
    assertResult(false)(bs.processing)
    assertResult(List(bs))(dao.listAllBackupSets().unwrappedFutureValue)
  }

  test("update backup set files") {
    val dao = new Dao(Executors.newCachedThreadPool())

    val bs = dao.createBackupSet("ahoj").unwrappedFutureValue

    val files = List(File("/aaTestDir/aaTestFile"), File("/aaTestDir/A77_32122.jpg"))
    dao.updateFilesInBackupSet(bs.id, files).unwrappedFutureValue
    assertResult(files.toSet)(dao.listFilesInBackupSet(bs.id).unwrappedFutureValue.toSet)

    val files2 = List(File("/aaTestDir/aaTestFile"), File("/aaTestDir/A77_32123.jpg"))
    dao.updateFilesInBackupSet(bs.id, files2).unwrappedFutureValue
    assertResult(files2.toSet)(dao.listFilesInBackupSet(bs.id).unwrappedFutureValue.toSet)
  }

  test("markAsExecutedNow") {
    val dao = new Dao(Executors.newCachedThreadPool())

    dao.createBackupSet("ahoj").unwrappedFutureValue
    val List(bs) = dao.listAllBackupSets().unwrappedFutureValue
    assertResult(None)(bs.lastExecution)
    assertResult(false)(bs.processing)

    DB.autoCommit { implicit session =>
      sql"update backup_sets set processing = true where id = ${bs.id}".update().apply()
    }

    dao.markAsExecutedNow(bs.id).unwrappedFutureValue

    val List(bsu) = dao.listAllBackupSets().unwrappedFutureValue
    assert(bsu.lastExecution.nonEmpty)
  }

  test("markAsProcessing") {
    val dao = new Dao(Executors.newCachedThreadPool())

    dao.createBackupSet("ahoj").unwrappedFutureValue
    val List(bs) = dao.listAllBackupSets().unwrappedFutureValue
    assertResult(None)(bs.lastExecution)
    assertResult(false)(bs.processing)

    dao.markAsProcessing(bs.id).unwrappedFutureValue

    assertResult(true)(dao.listAllBackupSets().unwrappedFutureValue.head.processing)
  }

  test("listBackupSetsToExecute") {
    val dao = new Dao(Executors.newCachedThreadPool())

    dao.createBackupSet("ahoj").unwrappedFutureValue
    dao.createBackupSet("ahoj2").unwrappedFutureValue
    dao.createBackupSet("ahoj3").unwrappedFutureValue
    dao.createBackupSet("ahoj4").unwrappedFutureValue
    dao.createBackupSet("ahoj5").unwrappedFutureValue
    val List(bs, bs2, bs3, bs4, bs5) = dao.listAllBackupSets().unwrappedFutureValue

    DB.autoCommit { implicit session =>
      sql"update backup_sets set last_execution = now() where id = ${bs.id}".update().apply()
      sql"update backup_sets set frequency = 120, last_execution = DATEADD('HOUR', -3, now()) where id = ${bs2.id}".update().apply()
      sql"update backup_sets set frequency = 400, last_execution = DATEADD('HOUR', -3, now()) where id = ${bs3.id}".update().apply()
      sql"update backup_sets set frequency = 400, last_execution = DATEADD('HOUR', -7, now()) where id = ${bs4.id}".update().apply()
      sql"update backup_sets set processing = true, frequency = 120, last_execution = DATEADD('HOUR', -7, now()) where id = ${bs5.id}"
        .update()
        .apply()
    }

    assertResult(
      List(
        bs2.copy(frequency = Duration.ofMinutes(120)),
        bs4.copy(frequency = Duration.ofMinutes(400))
      ))(dao.listBackupSetsToExecute().unwrappedFutureValue.map(_.copy(lastExecution = None)))

  }

  override protected def beforeEach(): Unit = {
    ConnectionPool.singleton("jdbc:h2:tcp://localhost:1521/test", "sa", "")

    DB.autoCommit { implicit session =>
      DbScheme.create
      DbScheme.truncateAll
    }
  }

  override protected def afterEach(): Unit = {
    DBs.close()
  }

}
