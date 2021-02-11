package doobie.migration

import cats.effect.IO
import cats.instances.list._
import cats.syntax.applicativeError._
import cats.syntax.traverse._
import doobie._
import doobie.implicits._
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import scala.io.Source

case class DoobiePostgresMigrationException(msg: String, ex: Exception = null) extends Exception(msg, ex)

/**
  * Represents a DB schema migration
  *
  * @param id a unique id string that must be sorted higher than the last one applied. It has to be a number of 10 digits followed by a '_' and a string. By convention the number is the UTC time when it was created.
  * @param up the sql that will be applied to go 'up' or to the next version of the DB schema
  * @param down the sql that will be applied to rollback one version from current version of DB schema
  */
case class Migration private (id: String, up: String, down: String, md5: String)

object Migration {
  def create(id: String, up: String, down: String) = {
    Migration(id, up, down, md5Hash(id + up + down))
  }

  private[migration] def md5Hash(str: String): String = {
    MessageDigest.getInstance("MD5").digest(str.getBytes).map("%02X".format(_)).mkString
  }
}

/**
  * Schema migrations for Postgres using doobie
  */
object DoobiePostgresMigration {
  private val MigrationFileRegex = """^(\d{10}_.*)\.(up|down)\.sql$""".r
  private lazy val logger = LoggerFactory.getLogger(getClass)

  /**
    * Execute all migrations based in the directory. This will apply downs for any files present.
    * @param migrationsDir
    * @param xa
    * @param downMode if true, downs WILL be applied (so: downMode should be disabled in prod)
    * @param schema the name of the database schema (not the schema migrations)
    */
  def execute(migrationsDir: File, xa: Transactor[IO], schema: String = "public"): Unit = {
    try {
      executeMigrationsIO(migrationsDir, xa, schema).unsafeRunSync()
    } catch {
      case ex : Exception =>
        logger.error(s"Could not apply schema migrations:\n${ex.getMessage}", ex)
    }
  }

  def executeMigrationsIO(migrationsDir: File, xa: Transactor[IO], schema: String = "public"): IO[List[Migration]] = {
    import doobie.implicits._
    for {
      migrations <- getMigrations(migrationsDir)
      _ <- applyMigrations(migrations, schema).transact(xa)
    } yield migrations
  }

  private def idToUpFilePath(id: String) = id + ".up.sql"
  private def idToDownFilePath(id: String) = id + ".down.sql"

  private sealed trait MigrationFromFile {
    val id: String
  }
  private case class MigrationFromFileUp(id: String, up: String) extends MigrationFromFile
  private case class MigrationFromFileDown(id: String, down: String) extends MigrationFromFile

  def getMigrations(migrationsDir: File): IO[List[Migration]] = {

    def readLinesAsIO(file: File) = IO {
      val source = Source.fromFile(file)
      val ret = source.getLines.mkString("\n")
      source.close()
      ret
    }

    def getDataAndValidateMatchingFilename(file: File): IO[MigrationFromFile] = file.getName match {
      case MigrationFileRegex(id, "up") => for {
        content <- readLinesAsIO(file) // <- read up files
      } yield MigrationFromFileUp(id, content)
      case MigrationFileRegex(id, "down") => for {
        content <- readLinesAsIO((file))  // <- read down files
      } yield MigrationFromFileDown(id, content)
      case _ =>  // validate that there's only filenames matching our filename schema
        IO.raiseError(DoobiePostgresMigrationException(s"Found non-matching filename '${file.getAbsolutePath}'. All files in dir: '${migrationsDir.getAbsolutePath}' must match regex: ${MigrationFileRegex}"))
    }

    def getFileMigrationsAndValidateFileCount(eitherUpOrDown: List[MigrationFromFile]): IO[List[Migration]] = {
      val upAndDownsById = eitherUpOrDown.groupBy(_.id).toList
      upAndDownsById.traverse { case (id, upsAndDowns) =>
        val manyUps = upsAndDowns.filter {
          case MigrationFromFileUp(_, _) => true
          case _ => false
        }
        val manyDowns = upsAndDowns.filter {
          case MigrationFromFileDown(_, _) => true
          case _ => false
        }
        if (manyDowns.length <= 1 || manyUps.length <= 1) {
          val fileMigration = for {
            // these matches should never fail because manyUps/Downs should be 1 here
            MigrationFromFileUp(`id`, up) <- manyUps.headOption
            MigrationFromFileDown(`id`, down) <- manyDowns.headOption
          } yield Migration.create(id, up, down)
          fileMigration.map(IO.apply(_)).getOrElse {
            val upAbsolutePath = new File(migrationsDir, idToUpFilePath(id)).getAbsolutePath
            val downAbsolutePath = new File(migrationsDir, idToDownFilePath(id)).getAbsolutePath
            if (manyUps.length == 0 && manyDowns.length == 0) {
              IO.raiseError(DoobiePostgresMigrationException(s"Missing both up and down for id: $id. Searched files: $upAbsolutePath and $downAbsolutePath"))
            } else if (manyUps.length == 0) {
              IO.raiseError(DoobiePostgresMigrationException(s"Missing an .up file for id: $id. Searched files: $upAbsolutePath and $downAbsolutePath"))
            } else { // manyDowns.length == 0
              IO.raiseError(DoobiePostgresMigrationException(s"Missing a .down file for: $id. Searched files: $upAbsolutePath and $downAbsolutePath"))
            }
          }
        } else if (manyDowns.length > 1 || manyUps.length > 1) {
          IO.raiseError(DoobiePostgresMigrationException(s"Found many downs/ups for: $id in dir: '${migrationsDir.getAbsolutePath}'"))
        } else {
          IO.raiseError(DoobiePostgresMigrationException(s"Did not find EXACTLY these files: ${idToUpFilePath(id)} and ${idToDownFilePath(id)} in dir: '${migrationsDir.getAbsolutePath}'"))
        }
      }
    }

    val ioFiles = {
      Option(migrationsDir.listFiles()).map(IO.apply(_)).getOrElse {
        IO.raiseError(DoobiePostgresMigrationException(s"Could not read files from migrations directory: ${migrationsDir.getAbsolutePath}"))
      }
    }

    for {
      files <- ioFiles
      fileMigrationTuples <- files.foldLeft(IO(List.empty[MigrationFromFile])) { (prevIO, curr) =>
          for {
            prev <- prevIO
            fileMigrationTuple <- getDataAndValidateMatchingFilename(curr)
          } yield prev :+ fileMigrationTuple
      }
      schemaFiles <- getFileMigrationsAndValidateFileCount(fileMigrationTuples)
    } yield schemaFiles.sortBy(_.id)
  }

  private def idExists(m: Migration, fileMigrations: List[Migration]): Boolean = {
    fileMigrations.exists(fm => fm.id == m.id)
  }

  def applyMigrations(migrations: List[Migration], schema: String): ConnectionIO[Unit] = {
    val createSchemaMigration =
      (sql"""|CREATE TABLE IF NOT EXISTS """ ++ Fragment.const(schema + ".schema_migration") ++ sql""" (
            |  id TEXT PRIMARY KEY,
            |  md5 TEXT NOT NULL,
            |  up TEXT NOT NULL,
            |  down TEXT NOT NULL,
            |  created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now()
            |)
        """).stripMargin.update.run
    val validateSchemaSql =
      sql"""|SELECT 5 = (
            |  SELECT COUNT(*) FROM information_schema.columns
            |  WHERE table_name = 'schema_migration' AND table_schema = $schema AND (
            |    (lower(column_name) = 'id' AND upper(data_type) = 'TEXT' AND upper(is_nullable) = 'NO')
            |    OR
            |    (lower(column_name) = 'md5' AND upper(data_type) = 'TEXT' AND upper(is_nullable) = 'NO')
            |    OR
            |    (lower(column_name) = 'up' AND upper(data_type) = 'TEXT' AND upper(is_nullable) = 'NO')
            |    OR
            |    (lower(column_name) = 'down' AND upper(data_type) = 'TEXT' AND upper(is_nullable) = 'NO')
            |    OR
            |    (lower(column_name) = 'created_at' AND upper(data_type) = 'TIMESTAMP WITHOUT TIME ZONE' AND upper(is_nullable) = 'NO')
            |  )
            |)
        """.stripMargin
    val findMigrations =
      sql"""|SELECT id, up, down, md5 FROM schema_migration
            |ORDER BY id DESC
            |""".stripMargin.query[Migration].to[List]
    for {
      // step 1: create schema_migration.
      //         We CREATE IF NOT EXISTS because it is fine if it is already there.
      _ <- createSchemaMigration

      // step 2: validate that schema_migration is not bogus.
      //         We do this because we could have skipped it earlier since we do CREATE IF NOT EXISTS.
      validated <- validateSchemaSql.query[Boolean].unique
      _ <- if (!validated) {
        FC.raiseError(DoobiePostgresMigrationException(s"Could not validate `schema_migration` table via: $validateSchemaSql"))
      } else FC.unit

      alreadyRanMigrations <- findMigrations

      // step 3: Complain if we're missing any migration files
      _ <- alreadyRanMigrations.filter(m => !idExists(m, migrations)) match {
        case removed if removed.nonEmpty =>
          FC.raiseError(
            DoobiePostgresMigrationException(
              s"Could not find expected migration files with id ${removed.map(_.id).mkString(", ")}. If you really want to remove these, run the down file manually and delete the corresponding rows from the `schema_migration` table"
            )
          )
        case _ => FC.unit
      }

      // step 4: apply up migrations and check existing ones
      existingHashByIdFromDb = alreadyRanMigrations.groupBy(_.id)
      dbIds = alreadyRanMigrations.map(_.id)
      highestCurrentId = if (dbIds.nonEmpty) {
        dbIds.max
      } else dbIds.headOption.getOrElse("")

      _ <- migrations.traverse[ConnectionIO, Unit] { curr =>
        val id = curr.id
        val fileHash = curr.md5
        existingHashByIdFromDb.get(id) match {
          case None => // DB didn't know about this file, so we will apply ups
            val upMigrations = for {
              _ <- Update0(curr.up, None).run.handleErrorWith { case ex: Exception =>
                FC.raiseError(DoobiePostgresMigrationException(s"Failed while applying ups from '$id':\n${curr.up}", ex))
              }
              _ <- if (id < highestCurrentId) {
                FC.raiseError(DoobiePostgresMigrationException(s"Cannot apply migration! Id: '$id' is 'lower' than: '$highestCurrentId'"))
              } else FC.unit
              _ <-
                sql"""|INSERT INTO schema_migration(id, md5, up, down)
                      |VALUES ($id, $fileHash, ${curr.up}, ${curr.down})
                      |""".stripMargin.update.run
            } yield ()
            for {
              _ <- FC.delay(logger.info(s"Applying ups from '${idToUpFilePath(id)}'..."))
              _ <- upMigrations
            } yield ()
          case Some(Seq(Migration(`id`, _, _, `fileHash`))) =>
            FC.delay(logger.debug(s"Schema and id matches for '$id'. Skipping..."))
          case Some(Seq(Migration(`id`, _, _, md5))) =>
            FC.raiseError(DoobiePostgresMigrationException(s"Wrong hash for '$id'! DB says: '$md5'. Current is: '$fileHash'. Did files: ${idToDownFilePath(id)} and ${idToUpFilePath(id)} change?"))
          case Some(alts) =>
            FC.raiseError(DoobiePostgresMigrationException(s"Expected to find one or zero pairs with id: '$id', but found: ${alts.mkString(",")}")) // can only happen if id is no longer a PRIMARY KEY
        }
      }
    } yield ()
  }
}
