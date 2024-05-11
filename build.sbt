ThisBuild / scalaVersion := "3.4.1"

val PekkoVersion = "1.0.2"
val PekkoHttpVersion = "1.0.1"

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion,
  "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
  "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion
)

libraryDependencies += "org.apache.pekko" %% "pekko-cluster-typed" % PekkoVersion

lazy val root = (project in file("."))
  .settings(name := "exeCUTE")


