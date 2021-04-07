name := "doobie-postgres-migration"
organization := "no.nrk"
version := "0.9.1"
description := "Postgresql schema migrations for doobie"
scalaVersion := "2.13.1"
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
scalacOptions ++= Seq("-deprecation", "-feature", "-target:jvm-1.8", "-Ywarn-unused")

val doobieVersion = "0.12.1"

libraryDependencies ++= Seq(
  "org.tpolecat" %% "doobie-core" % doobieVersion,
  "org.tpolecat" %% "doobie-postgres" % doobieVersion,
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "ch.qos.logback" % "logback-core" % "1.2.3",
  "com.typesafe" % "config" % "1.3.2" % Test,
  "org.scalatest" %% "scalatest" % "3.1.0" % Test
)

publishTo := {
  val MyGet = "https://www.myget.org/F/nrk/maven"
  if (isSnapshot.value) Some("snapshots" at MyGet) else Some("releases" at MyGet)
}

credentials ++= Option(System.getenv("FILES")).toSeq.map(dir => Credentials(file(dir) / ".credentials"))
