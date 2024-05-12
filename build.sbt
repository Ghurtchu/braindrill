ThisBuild / scalaVersion := "3.4.1"

val PekkoVersion = "1.0.2"
val PekkoHttpVersion = "1.0.1"

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion,
  "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
  "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion
)

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.6"

libraryDependencies += "org.apache.pekko" %% "pekko-cluster-typed" % PekkoVersion

libraryDependencies += "org.apache.pekko" %% "pekko-serialization-jackson" % PekkoVersion


lazy val root = (project in file("."))
  .settings(name := "exeCUTE")


