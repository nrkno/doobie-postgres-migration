## What?

This library executes SQL migration scripts and keeps track of their meta information.

Scripts are be executed exactly once and any changes to scripts will cause an error notifying you of a corrupted database. If a migration script is removed, it will be rolled back.

The meta information consists of:

- an MD5 checksum of the executed script to make sure already existing scripts cannot be modified in your production system.
- a time-stamp of the date of execution so you can easily track when a change happened.
- a 'up' that is applied when going to the next version of the DB schema
- a 'down' that is applied when rolling back a version of the DB schema 

All migrations are executed in one transaction, so if anything fails, it will rollback and throw an error.
It is recommended that the application be halted in the case of an exception.

## Why?

Database migrations should not be hard. They should be under version control and documented in both your production systems and in your project files.

## How?

This library must be used from your application.

Typically this would happen at the startup of the application.

```scala
import java.io.File
import doobie._
import doobie.migration.{DoobiePostgresMigration, DoobiePostgresMigrationException}
import cats.effect.IO

val xa : Aux[IO, Unit] = ??? // TODO
try {
  DoobiePostgresMigration.execute(new File("./migrations"), xa) // assumes you have migrations in this dir
} catch (ex) {
  case ex : DoobiePostgresMigrationException(ex) =>
    // print error and exit
    throw new ex
}

```

### Migration file layout

This library assumes you have your migration `.sql` files are in the following layout:
`<10 digits (i.e. output of date +%s)>_<description>.[up|down].sql`

Examples: `1537861520_hat_init.down.sql`, `1537861520_hat_init.up.sql`.

For each id (`<10 digits (i.e. output of date +%s)>_<description>`) you need ONE `.up` and ONE `.down` file.

Every subsequent file should have higher digits than the last applied migration.

Check out this directory `src/test/resources/migrations_test_working` for more examples. 

### Installation

Currently there is no published JARs for this library.
However, it is possible to use sources via Git in sbt like this:

```scala
// build.sbt
lazy val doobiePostgresMigration = ProjectRef(uri("git://github.com/nrkno/doobie-postgres-migration.git"), "doobie-postgres-migration")

lazy val root = (project in file(".")).dependsOn(doobiePostgresMigration)
```

## Tests
### Requirements
- sbt
- docker
 
To execute all tests do this:

`sbt test`

This will download a docker postgres image, start it and execute the tests.

If you are running this from an editor like IDEA, you have to start your own Postgres.
To use the default configured URL, user and password run this:

```docker run --name postgres -e POSTGRES_PASS=postgres -e POSTGRES_USER=postgres -e POSTGRES_DB=postgres -p 5432:5432 -d postgres:10.5```
