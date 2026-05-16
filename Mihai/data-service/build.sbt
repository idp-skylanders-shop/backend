val scala3Version = "3.3.1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "data-service",
    version := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "cask"        % "0.9.1",
      "com.lihaoyi" %% "upickle"     % "3.1.3",
      "io.getquill" %% "quill-jdbc"  % "4.8.0",
      "org.postgresql" % "postgresql" % "42.7.3"
    ),

    assembly / mainClass       := Some("dataservice.DataService"),
    assembly / assemblyJarName := "data-service.jar",

    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("io", "getquill", _*)        => MergeStrategy.first
      case PathList("META-INF", "versions", _*)  => MergeStrategy.discard
      case PathList("META-INF", _*)              => MergeStrategy.discard
      case "module-info.class"                   => MergeStrategy.discard
      case _                                     => MergeStrategy.first
    }
  )
