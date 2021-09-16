val doobieVersion = "1.0.0-RC1"

name := "doobie-postgres-migration"
organization := "no.nrk"
version := doobieVersion
description := "Postgresql schema migrations for doobie"
crossScalaVersions := List("2.13.6", "3.0.2")
scalaVersion := "2.13.6"
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

libraryDependencies ++= Seq(
  "org.tpolecat" %% "doobie-core" % doobieVersion,
  "org.tpolecat" %% "doobie-postgres" % doobieVersion,
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "ch.qos.logback" % "logback-core" % "1.2.3",
  "com.typesafe" % "config" % "1.4.1" % Test,
  "org.scalatest" %% "scalatest" % "3.2.9" % Test
)

publishTo := {
  val MyGet = "https://www.myget.org/F/nrk/maven"
  if (isSnapshot.value) Some("snapshots" at MyGet) else Some("releases" at MyGet)
}

credentials ++= Option(System.getenv("FILES")).toSeq.map(dir => Credentials(file(dir) / ".credentials"))
