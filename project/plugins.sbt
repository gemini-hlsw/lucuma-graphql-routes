resolvers += Resolver.sonatypeRepo("snapshots")
resolvers += "sonatype-s01-snapshots".at(
  "https://s01.oss.sonatype.org/content/repositories/snapshots"
)

addSbtPlugin("edu.gemini"       % "sbt-lucuma-lib" % "0.8.5")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"    % "0.6.3")
