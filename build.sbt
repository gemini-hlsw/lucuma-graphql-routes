val circeVersion               = "0.14.14"
val clueVersion                = "0.46.0"
val fs2Version                 = "3.12.0"
val grackleVersion             = "0.24.0"
val http4sVersion              = "0.23.30"
val http4sBlazeVersion         = "0.23.17"
val http4sJdkHttpClientVersion = "0.10.0"
val log4catsVersion            = "2.7.1"
val logbackVersion             = "1.5.18"
val munitVersion               = "1.1.1"
val munitCatsEffectVersion     = "2.1.0"
val natchezVersion             = "0.3.8"

enablePlugins(NoPublishPlugin)

ThisBuild / tlVersionIntroduced := Map("3" -> "0.3.3")
ThisBuild / tlBaseVersion := "0.10"
ThisBuild / scalaVersion       := "3.7.2"
ThisBuild / crossScalaVersions := Seq("3.7.2")

// Tests work fine in parallel but the output get interleaved, which can be confusing.
// It's fast so there's no harm doing them sequentially here.
ThisBuild / Test / parallelExecution := false

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name := "lucuma-graphql-routes",
    libraryDependencies ++= Seq(
      "edu.gemini"     %% "clue-model"             % clueVersion,
      "org.http4s"     %% "http4s-circe"           % http4sVersion,
      "org.http4s"     %% "http4s-dsl"             % http4sVersion,
      "org.http4s"     %% "http4s-server"          % http4sVersion,
      "org.tpolecat"   %% "natchez-core"           % natchezVersion,
      "org.typelevel"  %% "grackle-core"           % grackleVersion,
      "org.typelevel"  %% "log4cats-core"          % log4catsVersion,
      "ch.qos.logback" %  "logback-classic"        % logbackVersion             % Test,
      "edu.gemini"     %% "clue-http4s"            % clueVersion                % Test,
      "io.circe"       %% "circe-literal"          % circeVersion               % Test,
      "org.http4s"     %% "http4s-blaze-server"    % http4sBlazeVersion         % Test,
      "org.http4s"     %% "http4s-jdk-http-client" % http4sJdkHttpClientVersion % Test,
      "org.scalameta"  %% "munit"                  % munitVersion               % Test,
      "org.typelevel"  %% "grackle-circe"          % grackleVersion             % Test,
      "org.typelevel"  %% "log4cats-slf4j"         % log4catsVersion            % Test,
      "org.typelevel"  %% "munit-cats-effect"    % munitCatsEffectVersion     % Test,
    ),
  )
