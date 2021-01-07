package doobie.migration

import java.io.File

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import doobie.util.transactor.Transactor.Aux
import org.postgresql.util.PSQLException
import org.scalactic.source
import org.scalatest.{Assertion, FunSuite, IOMatchers, Resources, Succeeded}

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.Either

class DoobiePostgresMigrationTest extends FunSuite with IOMatchers {

  import doobie._

  private lazy val config = ConfigFactory.load("test.conf")

  private lazy val pgUrl = config.getString("postgres.url")
  private lazy val pgUser = config.getString("postgres.user")
  private lazy val pgPass = config.getString("postgres.pass")

  implicit val contextShift = IO.contextShift(ExecutionContext.global)

  // used only to create DBs
  lazy val hostXa: Aux[IO, _] = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", pgUrl, pgUser, pgPass
  )

  val testMigrations = List(
    Migration.create("1537861520_hat_init",
      """
        |CREATE TABLE hat(name TEXT PRIMARY KEY);
        |
        |CREATE TABLE feather(name TEXT REFERENCES hat(name));
      """.stripMargin,
      """
        |DROP TABLE feather CASCADE;
        |
        |DROP TABLE hat CASCADE;
      """.stripMargin),
    Migration.create("1537861521_ribbon_init",
      """
        |CREATE TABLE ribbon(color_spelling_mistake TEXT, hat TEXT REFERENCES hat(name));
      """.stripMargin,
      """
        |DROP TABLE ribbon CASCADE;
      """.stripMargin),
    Migration.create("1537861522_ribbon_change",
      """
        |ALTER TABLE ribbon RENAME COLUMN color_spelling_mistake TO color;
      """.stripMargin,
      """
        |ALTER TABLE ribbon RENAME COLUMN color TO color_spelling_mistake;
      """.stripMargin)
  )

  val DefaultSchema = "public"
  test("basic apply migrations") {
    TestUtils.withTestDb(hostXa, pgUrl, pgUser, pgPass, "apply_migrations") { xa =>
      import doobie.implicits._
      import cats.implicits._

      for {
        // apply all ups
        _ <- DoobiePostgresMigration.applyMigrations(testMigrations, DefaultSchema).transact(xa)
        testHatName = "fedora"
        _ <- sql"""INSERT INTO hat(name) values($testHatName);""".update.run.transact(xa)
        _ <- sql"""INSERT INTO ribbon(color, hat) values('red', $testHatName);""".update.run.transact(xa)
      } yield ()
    }
  }

  test("read migrations directory") {
    val migrations = DoobiePostgresMigration.getMigrations(new File("./src/test/resources/migrations_test_working_1")).unsafeRunSync()
    assertResult(3)(migrations.length)
    assertThrows[DoobiePostgresMigrationException] {
      DoobiePostgresMigration.getMigrations(new File("./src/test/resources/migrations_test_illegal_file")).unsafeRunSync()
    }
    assertThrows[DoobiePostgresMigrationException] {
      DoobiePostgresMigration.getMigrations(new File("./src/test/resources/migrations_test_missing_file")).unsafeRunSync()
    }
    assertThrows[DoobiePostgresMigrationException] {
      DoobiePostgresMigration.getMigrations(new File("./src/test/resources/migrations_test_does_not_exist")).unsafeRunSync()
    }
  }

  test("execute migrations") {
    TestUtils.withTestDb(hostXa, pgUrl, pgUser, pgPass, "read_dir_apply_migrations") { xa =>
      import doobie.implicits._

      for {
        _ <- DoobiePostgresMigration.executeMigrationsIO(new File("./src/test/resources/migrations_test_working_1"), xa)
        testHatName = "fedora"
        _ <- sql"""INSERT INTO hat(name) values($testHatName);""".update.run.transact(xa)
      } yield ()
    }
  }

  test("another test") {
    TestUtils.withTestDb(hostXa, pgUrl, pgUser, pgPass, "change_migrations") { xa =>
      import doobie.implicits._

      val testHatName1 = "fedora"
      for {
        // here we image we have a "branch" where ribbon has a column color_spelling_mistake which was fixed by changing it to color
        _ <- DoobiePostgresMigration.executeMigrationsIO(new File("./src/test/resources/migrations_test_working_1"), xa)
        _ <- sql"""INSERT INTO hat(name) values($testHatName1);""".update.run.transact(xa)
        _ <- sql"""INSERT INTO ribbon(color, hat) values('red', $testHatName1);""".update.run.transact(xa)
      } yield ()
    }
  }
}
