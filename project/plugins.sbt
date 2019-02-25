logLevel := Level.Warn

resolvers += Resolver.bintrayIvyRepo("rallyhealth", "sbt-plugins")

//addSbtPlugin("com.rallyhealth.sbt" % "sbt-git-versioning" % "1.2.1")
addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.15")
addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.4")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.3.3")