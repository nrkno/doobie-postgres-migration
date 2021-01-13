package doobie.migration

import cats.effect.IO
import cats.instances.list._
import cats.syntax.traverse._
import doobie._
import doobie.implicits._
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

object TestUtils {
  implicit val contextShift = IO.contextShift(ExecutionContext.global)

  val testDbPrefix = "doobie_migration_test_"

  def getTestDbName(uniqueTestDbNameSuffix: String) = {
    testDbPrefix + uniqueTestDbNameSuffix
  }

  private lazy val logger = LoggerFactory.getLogger(getClass)

  def withTestDb[A](testXa: Transactor[IO], url: String, user: String, pass: String, uniqueTestDbNameSuffix: String)(f: Transactor[IO] => IO[A]) = {

    // setup
    val testDbName = getTestDbName(uniqueTestDbNameSuffix)

    def rawRunSync(sqlStr: String) = testXa.rawTrans.apply(Update0(sqlStr, None).run)

    val testIO = for {
      maybeExistingTestDbName <- sql"SELECT datname FROM pg_database WHERE datname = $testDbName".query[String].option.transact(testXa)
      // clean previous DB (from previous runs)
      _ <- maybeExistingTestDbName.toList.traverse { existingTestDbName =>
        rawRunSync(s"""DROP DATABASE "$existingTestDbName"""") *>
          IO.delay(logger.debug(s"Dropped test DB: $existingTestDbName"))
      }
      // create test DB
      _ <- rawRunSync(s"""CREATE DATABASE "$testDbName"""")
      _ <- IO(logger.debug(s"Created test DB: $testDbName"))
      // create test xa
      testUrl = {
        val parts = url.split("/")
        parts.init.mkString("/") + "/" + testDbName
      }
      _ <- IO(logger.debug(s"Creating xa from test url: $testUrl"))
      thisTestXa = Transactor.fromDriverManager[IO](
        "org.postgresql.Driver", testUrl, user, pass
      )
      res <- f(thisTestXa)
    } yield res

    testIO.unsafeRunSync
  }
}