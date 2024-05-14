ThisBuild / scalaVersion := "3.4.1"

val PekkoVersion = "1.0.2"
val PekkoHttpVersion = "1.0.1"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.discard
  case PathList("module-info.class") => MergeStrategy.discard
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion,
  "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
  "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion excludeAll(
    ExclusionRule("com.fasterxml.jackson.core", "jackson-core"),
    ExclusionRule("com.fasterxml.jackson.core", "jackson-databind"),
    ExclusionRule("com.fasterxml.jackson.module", "jackson-module-parameter-names"),
    ExclusionRule("com.fasterxml.jackson.datatype", "jackson-datatype-jdk8"),
    ExclusionRule("com.fasterxml.jackson.datatype", "jackson-datatype-jsr310"),
    ExclusionRule("com.fasterxml.jackson.dataformat", "jackson-dataformat-cbor")
  ),
  "org.apache.pekko" %% "pekko-cluster-typed" % PekkoVersion,
  "org.apache.pekko" %% "pekko-serialization-jackson" % PekkoVersion,
  "ch.qos.logback" % "logback-classic" % "1.5.6"
)

lazy val root = (project in file("."))
  .settings(
    name := "braindrill",
    assembly / assemblyJarName := "braindrill.jar",
    assembly / mainClass := Some("BrainDrill"),
  )
