val attoVersion                 = "0.8.0"
val catsVersion                 = "2.1.1"
val catsEffectVersion           = "2.1.3"
val catsTestkitScalaTestVersion = "1.0.1"
val circeOpticsVersion          = "0.13.0"
val circeVersion                = "0.13.0"
val http4sVersion               = "0.21.4"
val jawnVersion                 = "1.0.0"
val kindProjectorVersion        = "0.10.3"
val logbackVersion              = "1.2.3"
val log4catsVersion             = "1.1.1"
val monocleVersion              = "2.0.4"
val gspCoreVersion              = "0.2.4"
val gspMathVersion              = "0.2.2"
val sangriaVersion              = "2.0.0"
val sangriaCirceVersion         = "1.3.0"
val shapelessVersion            = "2.3.3"
val testContainersVersion       = "0.37.0"

inThisBuild(Seq(
  homepage := Some(url("https://github.com/gemini-hlsw")),
  addCompilerPlugin("org.typelevel" %% "kind-projector" % kindProjectorVersion),
  scalaVersion := "2.13.2"
) ++ gspPublishSettings)

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel"     %% "cats-testkit"           % catsVersion % "test",
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
  .enablePlugins(AutomateHeaderPlugin, CodegenPlugin)
  .settings(commonSettings)
  .settings(
    name := "gem-odb-api-core",
    libraryDependencies ++= Seq(
      "com.github.julien-truffaut" %% "monocle-core"           % monocleVersion,
      "org.sangria-graphql"        %% "sangria"                % sangriaVersion,
      "org.sangria-graphql"        %% "sangria-circe"          % sangriaCirceVersion,
      "edu.gemini"                 %% "gsp-core-model"         % gspCoreVersion,
      "edu.gemini"                 %% "gsp-math"               % gspMathVersion,
      "org.tpolecat"               %% "atto-core"              % attoVersion,
      "org.typelevel"              %% "cats-core"              % catsVersion,
      "org.typelevel"              %% "cats-effect"            % catsEffectVersion,
      "io.circe"                   %% "circe-core"             % circeVersion,
      "io.circe"                   %% "circe-literal"          % circeVersion,
      "io.circe"                   %% "circe-optics"           % circeOpticsVersion,
      "io.circe"                   %% "circe-parser"           % circeVersion,
      "org.typelevel"              %% "jawn-parser"            % jawnVersion,
      "com.chuusai"                %% "shapeless"              % shapelessVersion,
      "io.chrisdavenport"          %% "log4cats-slf4j"         % log4catsVersion,
      "ch.qos.logback"             %  "logback-classic"        % logbackVersion,
      "org.http4s"                 %% "http4s-blaze-server"    % http4sVersion,
      "org.http4s"                 %% "http4s-blaze-client"    % http4sVersion,
      "org.http4s"                 %% "http4s-circe"           % http4sVersion,
      "org.http4s"                 %% "http4s-dsl"             % http4sVersion
    )
  )

lazy val service = project
  .in(file("modules/service"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "gem-odb-api-service",
    libraryDependencies ++= Seq(
      "com.github.julien-truffaut" %% "monocle-core"           % monocleVersion,
      "org.sangria-graphql"        %% "sangria"                % sangriaVersion,
      "org.sangria-graphql"        %% "sangria-circe"          % sangriaCirceVersion,
      "edu.gemini"                 %% "gsp-core-model"         % gspCoreVersion,
      "edu.gemini"                 %% "gsp-math"               % gspMathVersion,
      "org.tpolecat"               %% "atto-core"              % attoVersion,
      "org.typelevel"              %% "cats-core"              % catsVersion,
      "org.typelevel"              %% "cats-effect"            % catsEffectVersion,
      "io.circe"                   %% "circe-core"             % circeVersion,
      "io.circe"                   %% "circe-literal"          % circeVersion,
      "io.circe"                   %% "circe-optics"           % circeOpticsVersion,
      "io.circe"                   %% "circe-parser"           % circeVersion,
      "org.typelevel"              %% "jawn-parser"            % jawnVersion,
      "com.chuusai"                %% "shapeless"              % shapelessVersion,
      "io.chrisdavenport"          %% "log4cats-slf4j"         % log4catsVersion,
      "ch.qos.logback"             %  "logback-classic"        % logbackVersion,
      "org.http4s"                 %% "http4s-blaze-server"    % http4sVersion,
      "org.http4s"                 %% "http4s-blaze-client"    % http4sVersion,
      "org.http4s"                 %% "http4s-circe"           % http4sVersion,
      "org.http4s"                 %% "http4s-dsl"             % http4sVersion
    )
  )
