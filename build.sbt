val clueVersion                 = "0.18.6"
val fs2Version                  = "3.0.6"
val grackleVersion              = "0.1.14"
val http4sVersion               = "0.23.6"
val kindProjectorVersion        = "0.13.2"
val log4catsVersion             = "2.1.1"
val sangriaCirceVersion         = "1.3.2"
val sangriaVersion              = "2.1.3"

inThisBuild(Seq(
  addCompilerPlugin(("org.typelevel" % "kind-projector" % kindProjectorVersion).cross(CrossVersion.full)),
) ++ lucumaPublishSettings)

publish / skip := true

lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(AutomateHeaderPlugin)
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
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "lucuma-graphql-routes-sangria",
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
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "lucuma-graphql-routes-grackle",
    libraryDependencies ++= Seq(
      "edu.gemini" %% "gsp-graphql-core" % grackleVersion,
    ),
  )
