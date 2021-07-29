val clueVersion                 = "0.16.0"
val http4sVersion               = "0.23.0-RC1"
val lucumaSsoVersion            = "0.0.10"
val log4catsVersion             = "2.1.1"

inThisBuild(Seq(
) ++ lucumaPublishSettings)

publish / skip := true

lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "lucuma-graphql-routes-core",
    libraryDependencies ++= Seq(
      "edu.gemini"    %% "clue-model"                % clueVersion,
      "edu.gemini"    %% "lucuma-sso-backend-client" % lucumaSsoVersion,
      "org.http4s"    %% "http4s-server"             % http4sVersion,
      "org.typelevel" %% "log4cats-slf4j"            % log4catsVersion,
    ),
  )


