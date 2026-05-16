val scala3Version = "3.3.1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "myshop",
    version := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "com.lihaoyi"        %% "cask"             % "0.9.1",
      "com.lihaoyi"        %% "requests"         % "0.8.0",
      "com.lihaoyi"        %% "upickle"          % "3.1.3",
      "org.apache.commons"  % "commons-email"    % "1.5",
      "com.sun.mail"        % "javax.mail"       % "1.6.2",
      "com.sun.activation"  % "javax.activation" % "1.2.0",
      "com.rabbitmq"        % "amqp-client"      % "5.21.0"
    ),

    assembly / mainClass       := Some("app.App"),
    assembly / assemblyJarName := "app.jar",

    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", "versions", _*) => MergeStrategy.discard
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case "module-info.class"                  => MergeStrategy.discard
      case _                                    => MergeStrategy.first
    }
  )
