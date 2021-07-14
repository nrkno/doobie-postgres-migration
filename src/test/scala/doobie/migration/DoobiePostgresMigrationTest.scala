package doobie.migration

import java.io.File

import cats.effect.{ContextShift, IO}
import com.typesafe.config.ConfigFactory
import doobie._
import doobie.implicits._
import org.scalatest.IOMatchers
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.ExecutionContext

class DoobiePostgresMigrationTest extends AnyFunSuite with IOMatchers {
  private lazy val config = ConfigFactory.load("test.conf")
  private lazy val pgUrl = config.getString("postgres.url")
  private lazy val pgUser = config.getString("postgres.user")
  private lazy val pgPass = config.getString("postgres.pass")

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  // used only to create DBs
  lazy val hostXa: Transactor[IO] = Transactor.fromDriverManager[IO](
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
      for {
        _ <- DoobiePostgresMigration.executeMigrationsIO(new File("./src/test/resources/migrations_test_working_1"), xa)
        testHatName = "fedora"
        _ <- sql"""INSERT INTO hat(name) values($testHatName);""".update.run.transact(xa)
      } yield ()
    }
  }

  test("another test") {
    TestUtils.withTestDb(hostXa, pgUrl, pgUser, pgPass, "change_migrations") { xa =>
      val testHatName1 = "fedora"
      for {
        // here we image we have a "branch" where ribbon has a column color_spelling_mistake which was fixed by changing it to color
        _ <- DoobiePostgresMigration.executeMigrationsIO(new File("./src/test/resources/migrations_test_working_1"), xa)
        _ <- sql"""INSERT INTO hat(name) values($testHatName1);""".update.run.transact(xa)
        _ <- sql"""INSERT INTO ribbon(color, hat) values('red', $testHatName1);""".update.run.transact(xa)
      } yield ()
    }
  }

  test("fail on missing migration") {
    TestUtils.withTestDb(hostXa, pgUrl, pgUser, pgPass, "missing_migrations") { xa =>
      for {
        // here we image we have a "branch" where ribbon has a column color_spelling_mistake which was fixed by changing it to color
        migrations <- DoobiePostgresMigration.getMigrations(new File("./src/test/resources/migrations_test_working_1"))
        _ <- DoobiePostgresMigration.applyMigrations(migrations, "public").transact(xa)
        _ <- DoobiePostgresMigration.applyMigrations(migrations.tail, "public").transact(xa).attempt.flatMap {
          case Left(_) => IO.unit
          case Right(_) => IO.raiseError(new RuntimeException("Should have failed on missing migration"))
        }
      } yield ()
    }
  }

  test("fail on changed migration") {
    // change digest of first migration
    def rewrite(ms: List[Migration]): List[Migration] =
      ms match {
        case m :: tail => Migration.create(m.id, "--- added comment\n" + m.up, m.down) :: tail
        case Nil => sys.error("expected migrations")
      }

    TestUtils.withTestDb(hostXa, pgUrl, pgUser, pgPass, "change_migrations") { xa =>
      for {
        // here we image we have a "branch" where ribbon has a column color_spelling_mistake which was fixed by changing it to color
        migrations <- DoobiePostgresMigration.getMigrations(new File("./src/test/resources/migrations_test_working_1"))
        _ <- DoobiePostgresMigration.applyMigrations(migrations, "public").transact(xa)
        changedMigrations = rewrite(migrations)
        _ <- DoobiePostgresMigration.applyMigrations(changedMigrations, "public").transact(xa).attempt.flatMap {
          case Left(_) => IO.unit
          case Right(_) => IO.raiseError(new RuntimeException("Should have failed on missing migration"))
        }
      } yield ()
    }
  }
}
