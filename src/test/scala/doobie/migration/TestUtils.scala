package doobie.migration

import cats.effect.IO
import doobie.util.transactor.Transactor.Aux
import org.slf4j.LoggerFactory


object TestUtils {
  import doobie._
  import doobie.implicits._

  val testDbPrefix = "doobie_migration_test_"

  def getTestDbName(uniqueTestDbNameSuffix: String) = {
    testDbPrefix + uniqueTestDbNameSuffix
  }

  private lazy val logger = LoggerFactory.getLogger(getClass)

  def withTestDb[A](testXa: Aux[IO, Unit], url: String, user: String, pass: String, uniqueTestDbNameSuffix: String)(f : Aux[IO, Unit] => A) = {
    import doobie.implicits._
    import cats.implicits._

    // setup
    val testDbName = getTestDbName(uniqueTestDbNameSuffix)
    def rawRunSync(sqlStr: String) = testXa.rawTrans.apply(Update0(sqlStr, None).run).unsafeRunSync

    // clean previous DB (from previous runs)
    val maybeExistingTestDbName = sql"SELECT datname FROM pg_database WHERE datname = $testDbName".query[String].option.transact(testXa).unsafeRunSync
    maybeExistingTestDbName.foreach { existingTestDbName=>
      rawRunSync(s"""DROP DATABASE "$existingTestDbName"""")
      logger.debug(s"Dropped test DB: $existingTestDbName")
    }

    // create test DB
    rawRunSync(s"""CREATE DATABASE "$testDbName"""")
    logger.debug(s"Created test DB: $testDbName")

    // create test xa
    val testUrl = {
      val parts = url.split("/")
      parts.init.mkString("/") + "/" + testDbName
    }
    logger.debug(s"Creating xa from test url: $testUrl")

    val thisTestXa = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver", testUrl, user, pass
    )

    // do test
    f(thisTestXa)
  }
}