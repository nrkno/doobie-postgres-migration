lazy val dockerRunPlugin = ProjectRef(uri("git://github.com/nrkno/sbt-docker-run-plugin.git"), "sbt-docker-run-plugin")

lazy val root = (project in file(".")).dependsOn(dockerRunPlugin).aggregate(dockerRunPlugin)
