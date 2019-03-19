package doobie.migration

import java.io.File

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import doobie.util.transactor.Transactor.Aux
import org.postgresql.util.PSQLException
import org.scalatest.FunSuite

import scala.concurrent.ExecutionContext

class DoobiePostgresMigrationTest extends FunSuite {
  import doobie._

  private lazy val config = ConfigFactory.load("test.conf")

  private lazy val pgUrl = config.getString("postgres.url")
  private lazy val pgUser = config.getString("postgres.user")
  private lazy val pgPass = config.getString("postgres.pass")

  implicit val contextShift = IO.contextShift(ExecutionContext.global)

  // used only to create DBs
  lazy val hostXa: Aux[IO, Unit] = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", pgUrl, pgUser, pgPass
  )

  val testMigrations = List(
    Migration("1537861520_hat_init",
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
    Migration("1537861521_ribbon_init",
      """
        |CREATE TABLE ribbon(color_spelling_mistake TEXT, hat TEXT REFERENCES hat(name));
      """.stripMargin,
      """
        |DROP TABLE ribbon CASCADE;
      """.stripMargin),
    Migration("1537861522_ribbon_change",
      """
        |ALTER TABLE ribbon RENAME COLUMN color_spelling_mistake TO color;
      """.stripMargin,
      """
        |ALTER TABLE ribbon RENAME COLUMN color TO color_spelling_mistake;
      """.stripMargin)
  )

  test("basic apply migrations") {
    TestUtils.withTestDb(hostXa, pgUrl, pgUser, pgPass, "apply_migrations") { xa =>
      import doobie.implicits._
      import cats.implicits._

      // apply all ups
      DoobiePostgresMigration.applyMigrations(testMigrations).transact(xa).unsafeRunSync

      // apply one down
      DoobiePostgresMigration.applyMigrations(testMigrations.init).transact(xa).unsafeRunSync

      val testHatName = "fedora"
      sql"""INSERT INTO hat(name) values($testHatName);""".update.run.transact(xa).unsafeRunSync()
      assertThrows[PSQLException] {
        sql"""INSERT INTO ribbon(color, hat) values('red', $testHatName);""".update.run.transact(xa).unsafeRunSync
      }

      // apply all downs
      DoobiePostgresMigration.applyMigrations(List.empty).transact(xa).unsafeRunSync
      // apply all ups
      DoobiePostgresMigration.applyMigrations(testMigrations).transact(xa).unsafeRunSync

      // should now work, but data was lost... :/ such is life
      sql"""INSERT INTO hat(name) values($testHatName);""".update.run.transact(xa).unsafeRunSync()
      sql"""INSERT INTO ribbon(color, hat) values('red', $testHatName);""".update.run.transact(xa).unsafeRunSync
    }
  }

  test("read migrations directory") {
    val migrations = DoobiePostgresMigration.getMigrations(new File("./src/test/resources/migrations_test_working")).unsafeRunSync()
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

      DoobiePostgresMigration.execute(new File("./src/test/resources/migrations_test_working"), xa)

      val testHatName = "fedora"
      sql"""INSERT INTO hat(name) values($testHatName);""".update.run.transact(xa).unsafeRunSync()
    }
  }
}
