ThisBuild / tlBaseVersion := "0.3"

val clueVersion                 = "0.23-0934fe0-SNAPSHOT"
val fs2Version                  = "3.2.7"
val grackleVersion              = "0.2.0"
val http4sVersion               = "0.23.12"
val kindProjectorVersion        = "0.13.2"
val log4catsVersion             = "2.3.1"
val sangriaCirceVersion         = "1.3.2"
val sangriaVersion              = "3.0.0"

enablePlugins(NoPublishPlugin)

ThisBuild / scalaVersion := "2.13.8"
ThisBuild / crossScalaVersions := Seq("2.13.8", "3.1.2")

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

lazy val sangria = project
  .in(file("modules/sangria"))
  .dependsOn(core)
  .settings(
    name := "lucuma-graphql-routes-sangria",
    crossScalaVersions := Seq("2.13.8"),
    libraryDependencies ++= Seq(
      "org.sangria-graphql" %% "sangria"       % sangriaVersion,
      "org.sangria-graphql" %% "sangria-circe" % sangriaCirceVersion,
    ),
    scalacOptions ++= Seq(
      "-Ymacro-annotations",
      "-Ywarn-macros:after"
    ),
  )

lazy val grackle = project
  .in(file("modules/grackle"))
  .dependsOn(core)
  .settings(
    name := "lucuma-graphql-routes-grackle",
    libraryDependencies ++= Seq(
      "edu.gemini" %% "gsp-graphql-core" % grackleVersion,
    ),
  )
