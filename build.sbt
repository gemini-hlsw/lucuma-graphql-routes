val attoVersion                 = "0.9.2"
val catsEffectVersion           = "2.4.1"
val catsTestkitScalaTestVersion = "2.1.2"
val catsVersion                 = "2.5.0"
val circeOpticsVersion          = "0.13.0"
val circeVersion                = "0.13.0"
val cirisVersion                = "1.2.1"
val clueVersion                 = "0.11.3"
val fs2Version                  = "2.5.4"
val http4sVersion               = "0.21.21"
val jawnVersion                 = "1.1.0"
val kindProjectorVersion        = "0.11.3"
val logbackVersion              = "1.2.3"
val log4catsVersion             = "1.2.1"
val lucumaCoreVersion           = "0.7.9"
val lucumaSsoVersion            = "0.0.9"
val monocleVersion              = "2.1.0"
val refinedVersion              = "0.9.22"
val sangriaVersion              = "2.1.0"
val sangriaCirceVersion         = "1.3.1"
val singletonOpsVersion         = "0.5.2"

val munitVersion                = "0.7.23"
val disciplineMunitVersion      = "1.0.7"


inThisBuild(
  Seq(
    homepage := Some(url("https://github.com/gemini-hlsw")),
    addCompilerPlugin(
      ("org.typelevel" % "kind-projector" % kindProjectorVersion).cross(CrossVersion.full)
    )
  ) ++ gspPublishSettings
)

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel"     %% "cats-testkit"           % catsVersion                 % "test",
    "org.typelevel"     %% "cats-testkit-scalatest" % catsTestkitScalaTestVersion % "test"
  )
)

lazy val noPublishSettings = Seq(
  skip in publish := true
)

lazy val modules: List[ProjectReference] = List(
  core,
  service
)

lazy val `gem-odb-api` = project.in(file("."))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .aggregate(modules:_*)
  .disablePlugins(RevolverPlugin)

lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    name := "lucuma-odb-api-core",
    scalacOptions ++= Seq(
      "-Ymacro-annotations"
    ),
    libraryDependencies ++= Seq(
      "co.fs2"                     %% "fs2-core"                  % fs2Version,
      "com.github.julien-truffaut" %% "monocle-core"              % monocleVersion,
      "com.github.julien-truffaut" %% "monocle-state"             % monocleVersion,
      "com.github.julien-truffaut" %% "monocle-macro"             % monocleVersion,
      "org.sangria-graphql"        %% "sangria"                   % sangriaVersion,
      "org.sangria-graphql"        %% "sangria-circe"             % sangriaCirceVersion,
      "edu.gemini"                 %% "clue-model"                % clueVersion,
      "edu.gemini"                 %% "lucuma-core"               % lucumaCoreVersion,
      "edu.gemini"                 %% "lucuma-sso-backend-client" % lucumaSsoVersion,
      "org.tpolecat"               %% "atto-core"                 % attoVersion,
      "org.typelevel"              %% "cats-core"                 % catsVersion,
      "org.typelevel"              %% "cats-effect"               % catsEffectVersion,
      "io.circe"                   %% "circe-core"                % circeVersion,
      "io.circe"                   %% "circe-literal"             % circeVersion,
      "io.circe"                   %% "circe-optics"              % circeOpticsVersion,
      "io.circe"                   %% "circe-parser"              % circeVersion,
      "io.circe"                   %% "circe-generic"             % circeVersion,
      "io.circe"                   %% "circe-generic-extras"      % circeVersion,
      "io.circe"                   %% "circe-refined"             % circeVersion,
      "org.typelevel"              %% "jawn-parser"               % jawnVersion,
      "org.typelevel"              %% "log4cats-slf4j"            % log4catsVersion,
      "ch.qos.logback"             %  "logback-classic"           % logbackVersion,
      "eu.timepit"                 %% "singleton-ops"             % singletonOpsVersion,
      "eu.timepit"                 %% "refined"                   % refinedVersion,
      "eu.timepit"                 %% "refined-cats"              % refinedVersion,

      "edu.gemini"                 %% "lucuma-core-testkit"       % lucumaCoreVersion      % Test,
      "org.scalameta"              %% "munit"                     % munitVersion           % Test,
      "org.typelevel"              %% "discipline-munit"          % disciplineMunitVersion % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val service = project
  .in(file("modules/service"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "lucuma-odb-api-service",
    scalacOptions ++= Seq(
      "-Ymacro-annotations"
    ),
    libraryDependencies ++= Seq(
      "com.github.julien-truffaut" %% "monocle-core"              % monocleVersion,
      "org.sangria-graphql"        %% "sangria"                   % sangriaVersion,
      "org.sangria-graphql"        %% "sangria-circe"             % sangriaCirceVersion,
      "edu.gemini"                 %% "clue-model"                % clueVersion,
      "edu.gemini"                 %% "lucuma-core"               % lucumaCoreVersion,
      "edu.gemini"                 %% "lucuma-sso-backend-client" % lucumaSsoVersion,
      "org.tpolecat"               %% "atto-core"                 % attoVersion,
      "org.typelevel"              %% "cats-core"                 % catsVersion,
      "org.typelevel"              %% "cats-effect"               % catsEffectVersion,
      "io.circe"                   %% "circe-core"                % circeVersion,
      "io.circe"                   %% "circe-literal"             % circeVersion,
      "io.circe"                   %% "circe-optics"              % circeOpticsVersion,
      "io.circe"                   %% "circe-parser"              % circeVersion,
      "io.circe"                   %% "circe-generic"             % circeVersion,
      "io.circe"                   %% "circe-generic-extras"      % circeVersion,
      "is.cir"                     %% "ciris"                     % cirisVersion,
      "org.typelevel"              %% "jawn-parser"               % jawnVersion,
      "org.typelevel"              %% "log4cats-slf4j"            % log4catsVersion,
      "ch.qos.logback"             %  "logback-classic"           % logbackVersion,
      "org.http4s"                 %% "http4s-blaze-server"       % http4sVersion,
      "org.http4s"                 %% "http4s-blaze-client"       % http4sVersion,
      "org.http4s"                 %% "http4s-circe"              % http4sVersion,
      "org.http4s"                 %% "http4s-dsl"                % http4sVersion
    )
  ).enablePlugins(JavaAppPackaging)
