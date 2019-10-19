logLevel := Level.Warn

resolvers += Resolver.bintrayIvyRepo("rallyhealth", "sbt-plugins")

addSbtPlugin("com.rallyhealth.sbt" % "sbt-git-versioning" % "1.2.1")
addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.15")
addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.4")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.4.0")
addSbtPlugin("org.xerial.sbt" % "sbt-pack" % "0.12")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.4.1")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.10")
//addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.8")