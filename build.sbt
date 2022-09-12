import sbt.librarymanagement.InclExclRule

credentials += Credentials(new File(sys.env.get("HOME").get + "/.sbt/.credentials"))

lazy val root = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "com.scylladb",
      scalaVersion := "2.11.12"
    )),
  name      := "scylla-migrator",
  version   := "0.0.1",
  mainClass := Some("com.scylladb.migrator.Migrator"),
  javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
  javaOptions ++= Seq(
    "-Xms512M",
    "-Xmx2048M",
    "-XX:MaxPermSize=2048M",
    "-XX:+CMSClassUnloadingEnabled"),
  scalacOptions ++= Seq("-deprecation", "-unchecked", "-Ypartial-unification"),
  parallelExecution in Test := false,
  fork                      := true,
  scalafmtOnCompile         := true,
  libraryDependencies ++= Seq(
    "org.apache.spark" %% "spark-streaming"      % "2.4.4" % "provided",
    "org.apache.spark" %% "spark-sql"            % "2.4.4" % "provided",
    "org.apache.spark" %% "spark-sql"            % "2.4.4" % "provided",
    "com.amazonaws"    % "aws-java-sdk-sts"      % "1.11.728",
    "com.amazonaws"    % "aws-java-sdk-dynamodb" % "1.11.728",
    ("com.amazonaws" % "dynamodb-streams-kinesis-adapter" % "1.5.2")
      .excludeAll(InclExclRule("com.fasterxml.jackson.core")),
    "org.yaml"       % "snakeyaml"      % "1.23",
    "io.circe"       %% "circe-yaml"    % "0.9.0",
    "io.circe"       %% "circe-generic" % "0.9.0",
    "org.scalatest"  %% "scalatest"     % "3.0.1" % "test",
    "org.scalacheck" %% "scalacheck"    % "1.13.4" % "test"
  ),
  assemblyShadeRules in assembly := Seq(
    ShadeRule.rename("org.yaml.snakeyaml.**" -> "com.scylladb.shaded.@1").inAll
  ),
  assemblyMergeStrategy in assembly := {
    case PathList("org", "joda", "time", _ @_*)                       => MergeStrategy.first
    case PathList("org", "apache", "commons", "logging", _ @_*)       => MergeStrategy.first
    case PathList("com", "fasterxml", "jackson", "annotation", _ @_*) => MergeStrategy.first
    case PathList("com", "fasterxml", "jackson", "core", _ @_*)       => MergeStrategy.first
    case PathList("com", "fasterxml", "jackson", "databind", _ @_*)   => MergeStrategy.first
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  },
  // uses compile classpath for the run task, including "provided" jar (cf http://stackoverflow.com/a/21803413/3827)
  run in Compile := Defaults
    .runTask(fullClasspath in Compile, mainClass in (Compile, run), runner in (Compile, run))
    .evaluated,
  scalacOptions ++= Seq("-deprecation", "-unchecked"),
  pomIncludeRepository := { x =>
    false
  },
  resolvers ++= Seq(
    "Artifactory Release" at "https://zeotap.jfrog.io/zeotap/libs-release"
  ),
  pomIncludeRepository := { x =>
    false
  },
  // publish settings
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }
)
