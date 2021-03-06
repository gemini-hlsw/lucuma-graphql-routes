ThisBuild / tlBaseVersion := "0.4"

val clueVersion                 = "0.23.1"
val fs2Version                  = "3.2.7"
val grackleVersion              = "0.3.0"
val http4sVersion               = "0.23.13"
val kindProjectorVersion        = "0.13.2"
val log4catsVersion             = "2.4.0"
val natchezVersion              = "0.1.6"
val sangriaCirceVersion         = "1.3.2"
val sangriaVersion              = "3.0.1"

enablePlugins(NoPublishPlugin)

ThisBuild / scalaVersion := "2.13.8"
ThisBuild / crossScalaVersions := Seq("2.13.8", "3.1.2")
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

lazy val sangria = project
  .in(file("modules/sangria"))
  .dependsOn(core)
  .settings(
    name := "lucuma-graphql-routes-sangria",
    libraryDependencies ++= {
      if (tlIsScala3.value) {
        Nil
      } else {
        Seq(
          "org.sangria-graphql" %% "sangria"       % sangriaVersion,
          "org.sangria-graphql" %% "sangria-circe" % sangriaCirceVersion,
        )
      }
    },
    mimaPreviousArtifacts := {
      if (tlIsScala3.value) Set.empty else mimaPreviousArtifacts.value
    },
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
      "edu.gemini"   %% "gsp-graphql-core" % grackleVersion,
      "org.tpolecat" %% "natchez-core"     % natchezVersion,
    ),
  )
