val clueVersion                = "0.40.0"
val fs2Version                 = "3.2.7"
val grackleVersion             = "0.23.0"
val http4sVersion              = "0.23.30"
val kindProjectorVersion       = "0.13.2"
val log4catsVersion            = "2.7.0"
val munitCatsEffectVersion     = "2.0.0"
val munitVersion               = "1.0.3"
val natchezVersion             = "0.3.7"
val http4sBlazeVersion         = "0.23.17"
val http4sJdkHttpClientVersion = "0.9.2"
val logbackVersion             = "1.5.16"
val circeVersion               = "0.14.10"

enablePlugins(NoPublishPlugin)

ThisBuild / tlVersionIntroduced := Map("3" -> "0.3.3")
ThisBuild / tlBaseVersion := "0.8"
ThisBuild / scalaVersion       := "3.5.2"
ThisBuild / crossScalaVersions := Seq("3.5.2")

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
