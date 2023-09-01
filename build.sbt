ThisBuild / tlBaseVersion := "0.6"

val clueVersion                 = "0.32.0"
val fs2Version                  = "3.2.7"
val grackleVersion              = "0.14-74b6b5d-SNAPSHOT"
val http4sVersion               = "0.23.23"
val kindProjectorVersion        = "0.13.2"
val log4catsVersion             = "2.6.0"
val natchezVersion              = "0.3.3"

enablePlugins(NoPublishPlugin)

ThisBuild / scalaVersion := "2.13.11"
ThisBuild / crossScalaVersions := Seq("2.13.11", "3.3.0")
ThisBuild / tlVersionIntroduced := Map("3" -> "0.3.3")

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name := "lucuma-graphql-routes-core",
    libraryDependencies ++= Seq(
      "edu.gemini"    %% "clue-model"    % clueVersion,
      "org.http4s"    %% "http4s-server" % http4sVersion,
      "org.http4s"    %% "http4s-dsl"    % http4sVersion,
      "org.http4s"    %% "http4s-circe"  % http4sVersion,
      "org.typelevel" %% "log4cats-core" % log4catsVersion,
    ),
  )

lazy val grackle = project
  .in(file("modules/grackle"))
  .dependsOn(core)
  .settings(
    name := "lucuma-graphql-routes-grackle",
    libraryDependencies ++= Seq(
      "edu.gemini"   %% "gsp-graphql-core" % grackleVersion,
      "org.tpolecat" %% "natchez-core"     % natchezVersion,
    ),
  )
