val circeVersion               = "0.14.16"
val clueVersion                = "0.55.0"
val fs2Version                 = "3.12.0"
val grackleVersion             = "0.29.0"
val http4sVersion              = "0.23.36"
val http4sJdkHttpClientVersion = "0.10.0"
val log4catsVersion            = "2.8.0"
val logbackVersion             = "1.5.37"
val munitVersion               = "1.3.3"
val munitCatsEffectVersion     = "2.2.0"
val otel4sVersion              = "1.0.1"

enablePlugins(NoPublishPlugin)

ThisBuild / tlVersionIntroduced := Map("3" -> "0.3.3")
ThisBuild / tlBaseVersion       := "0.13"
ThisBuild / scalaVersion        := "3.8.4"
ThisBuild / crossScalaVersions  := Seq("3.8.4")

// Tests work fine in parallel but the output get interleaved, which can be confusing.
// It's fast so there's no harm doing them sequentially here.
ThisBuild / Test / parallelExecution := false

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name := "lucuma-graphql-routes",
    libraryDependencies ++= Seq(
      "edu.gemini"    %% "clue-model"                  % clueVersion,
      "org.http4s"    %% "http4s-circe"                % http4sVersion,
      "org.http4s"    %% "http4s-dsl"                  % http4sVersion,
      "org.http4s"    %% "http4s-server"               % http4sVersion,
      "org.typelevel" %% "otel4s-core-trace"           % otel4sVersion,
      "org.typelevel" %% "otel4s-semconv-experimental" % otel4sVersion,
      "org.typelevel" %% "grackle-core"                % grackleVersion,
      "org.typelevel" %% "log4cats-core"               % log4catsVersion,
      "ch.qos.logback" % "logback-classic"             % logbackVersion             % Test,
      "edu.gemini"    %% "clue-http4s"                 % clueVersion                % Test,
      "io.circe"      %% "circe-literal"               % circeVersion               % Test,
      "org.http4s"    %% "http4s-ember-server"         % http4sVersion              % Test,
      "org.http4s"    %% "http4s-jdk-http-client"      % http4sJdkHttpClientVersion % Test,
      "org.scalameta" %% "munit"                       % munitVersion               % Test,
      "org.typelevel" %% "grackle-circe"               % grackleVersion             % Test,
      "org.typelevel" %% "log4cats-slf4j"              % log4catsVersion            % Test,
      "org.typelevel" %% "munit-cats-effect"           % munitCatsEffectVersion     % Test
    )
  )
