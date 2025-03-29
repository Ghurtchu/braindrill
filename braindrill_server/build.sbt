ThisBuild / scalaVersion := "3.4.1"

val PekkoVersion = "1.0.2"
val PekkoHttpVersion = "1.0.1"
val PekkoManagementVersion = "1.0.0"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.discard
  case PathList("module-info.class")                              => MergeStrategy.discard
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion,
  "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
  "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
  "org.apache.pekko" %% "pekko-cluster-typed" % PekkoVersion,
  "org.apache.pekko" %% "pekko-serialization-jackson" % PekkoVersion,
  "org.apache.pekko" %% "pekko-http-cors" % PekkoHttpVersion,
  "ch.qos.logback" % "logback-classic" % "1.5.6"
)

lazy val root = (project in file("."))
  .settings(
    name := "braindrill",
    assembly / assemblyJarName := "braindrill.jar",
    assembly / mainClass := Some("BrainDrill")
  )
