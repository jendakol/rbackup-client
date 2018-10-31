package lib

import java.util.UUID

import cats.data.EitherT
import cats.syntax.all._
import com.typesafe.scalalogging.StrictLogging
import controllers.WsApiController
import fs2.async.mutable.Semaphore
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import lib.App._
import lib.CirceImplicits._
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}

import scala.collection.mutable
import scala.concurrent.duration.Duration

@Singleton
class TasksManager @Inject()(wsApiController: WsApiController)(implicit sch: Scheduler) extends StrictLogging {

  private val tasks = mutable.Map.empty[UUID, (CancelableFuture[Unit], RunningTask)]
  private val semaphore = Semaphore[Task](1).runSyncUnsafe(Duration.Inf)

  def start(rt: RunningTask, exec: Result[Unit]): Result[Unit] = EitherT.right {
    semaphore.decrement.bracket { _ =>
      logger.debug(s"Starting task: $rt")

      Task {
        val key = UUID.randomUUID()

        val future = exec.unwrapResult.cancelable
          .doOnFinish {
            case Some(t) =>
              Task {
                tasks -= key
                logger.debug(s"Task $rt failed", t)
              } >> notifyUi()

            case None =>
              Task {
                tasks -= key
                logger.debug(s"Task $rt was successful")
              } >> notifyUi()
          }
          .doOnCancel {
            Task {
              tasks -= key
              logger.debug(s"Task $rt was cancelled")
            } >> notifyUi()
          }
          .runAsync

        tasks += key -> (future, rt)

        ()
      } >> notifyUi()
    } { _ =>
      semaphore.increment
    }
  }

  def cancel(key: UUID): Result[Unit] = EitherT.right {
    semaphore.decrement.bracket { _ =>
      Task {
        tasks.get(key).foreach(_._1.cancel())
        // the task is removed and UI notified in callbacks on the task itself
      }
    } { _ =>
      semaphore.increment
    }
  }

  private def getAll: Task[Map[UUID, RunningTask]] = Task {
    tasks.mapValues(_._2).toMap
  }

  private def notifyUi(): Task[Unit] = {
    (for {
      runningTasks <- getAll.map(RunningTasks)
      _ = logger.debug(s"Sending running tasks: $runningTasks")
      _ <- wsApiController.send(controllers.WsMessage(`type` = "runningTasks", data = runningTasks.toJson)).unwrapResult
    } yield {
      ()
    }).doOnFinish {
      case Some(t) =>
        Task {
          logger.warn("Running tasks update to UI failed", t)
        }
      case None =>
        Task {
          logger.debug("Sent running tasks update to UI")
        }
    }
  }
}

sealed trait RunningTask {
  def toJson: Json
}

object RunningTask {

  case class FileUpload(fileName: String) extends RunningTask {
    override def toJson: Json = parseSafe(s"""{ "name": "file-upload", "data": { "file_name": "$fileName"} }""")
  }

}

case class RunningTasks(tasks: Map[UUID, RunningTask]) {
  def toJson: Json = tasks.mapValues(_.toJson).asJson
}
