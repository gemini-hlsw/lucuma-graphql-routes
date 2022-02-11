resolvers += Resolver.sonatypeRepo("snapshots")
resolvers += "sonatype-s01-snapshots".at(
  "https://s01.oss.sonatype.org/content/repositories/snapshots"
)

addSbtPlugin("edu.gemini"       % "sbt-lucuma-lib" % "0.6.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"    % "0.5.3")
