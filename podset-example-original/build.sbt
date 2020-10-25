organization in ThisBuild := "com.lightbend"

name := "podset-example-original"

scalaVersion := "2.13.3"

// make version compatible with docker for publishing
ThisBuild / dynverSeparator := "-"

scalacOptions := Seq("-feature", "-unchecked", "-deprecation", "-encoding", "utf8")
classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.AllLibraryJars

run / fork := false
Global / cancelable := false // ctrl-c
mainClass in (Compile, run) := Some("exmple.PodSetOperatorMain")

enablePlugins(JavaServerAppPackaging, DockerPlugin)

dockerUpdateLatest := true
dockerUsername := sys.props.get("docker.username")
dockerRepository := sys.props.get("docker.registry")
dockerBaseImage := "adoptopenjdk:11-jre-hotspot"

libraryDependencies ++= {
  Seq(
    "io.fabric8" % "kubernetes-client" % "4.12.0",
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.11.2",
    "org.slf4j" % "slf4j-api" % "1.7.30",
    "ch.qos.logback" % "logback-classic" % "1.2.3")
}
