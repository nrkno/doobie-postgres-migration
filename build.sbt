name := "doobie-postgres-migration"
organization := "no.nrk"
version := "0.7.1-SNAPSHOT"

description :=
  """
    |Postgresql schema migrations for doobie
  """.stripMargin

crossScalaVersions := Seq("2.12.8", "2.13.1")

val typesafeConfig = "com.typesafe" % "config" % "1.3.2"

val doobieVersion = "0.9.2"
val doobieCore = "org.tpolecat" %% "doobie-core" % doobieVersion
val doobiePostgres = "org.tpolecat" %% "doobie-postgres" % doobieVersion

val loggerLibs = Seq(
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "ch.qos.logback" % "logback-core" % "1.2.3",
)

val testLibs = Seq(
  "org.scalatest" %% "scalatest" % "3.1.0" % "test",
)

libraryDependencies ++= Seq(
  doobieCore,
  doobiePostgres,
  typesafeConfig % "test",
) ++ loggerLibs ++ testLibs

publishTo := {
  val MyGet = "https://www.myget.org/F/nrk/maven"
  if (isSnapshot.value)
    Some("snapshots" at MyGet)
  else
    Some("releases" at MyGet)
}

credentials ++= (for {
  dir <- Option(System.getenv("FILES")).toSeq
} yield Credentials(file(dir) / ".credentials"))
