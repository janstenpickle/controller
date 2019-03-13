import sbt.Keys.libraryDependencies
import sbt.url

val catsVer = "1.6.0"
val catsEffectVer = "1.2.0"
val circeVer = "0.11.1"
val extruderVer = "0.10.0"
val fs2Ver = "1.0.1"
val http4sVer = "0.20.0-M6"
val kittensVer = "1.2.1"
val refinedVer = "0.9.4"
val scalaCheckVer = "1.13.5"
val scalaCheckShapelessVer = "1.1.8"
val scalaTestVer = "3.0.5"

val commonSettings = Seq(
  organization := "io.janstenpickle",
  scalaVersion := "2.12.8",
  scalacOptions ++= Seq(
    "-unchecked",
    "-feature",
    "-deprecation:false",
    "-Xcheckinit",
    "-Xlint:-nullary-unit",
    "-Ywarn-numeric-widen",
    "-Ywarn-dead-code",
    "-Yno-adapted-args",
    "-language:_",
    "-target:jvm-1.8",
    "-encoding",
    "UTF-8"
  ),
  addCompilerPlugin(("org.spire-math" % "kind-projector" % "0.9.9").cross(CrossVersion.binary)),
//  addCompilerPlugin(("io.tryp"        % "splain"         % "0.4.0").cross(CrossVersion.patch)),
  publishMavenStyle := true,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  homepage := Some(url("https://github.com/janstenpickle/extruder")),
  developers := List(
    Developer(
      "janstenpickle",
      "Chris Jansen",
      "janstenpickle@users.noreply.github.com",
      url = url("https://github.com/janstepickle")
    )
  ),
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  },
  bintrayReleaseOnPublish := true,
  coverageMinimum := 80,
  coverageHighlighting := true,
  scalafmtOnCompile := true,
  scalafmtTestOnCompile := true,
  parallelExecution in ThisBuild := true,
  logBuffered in Test := false,
  libraryDependencies ++= Seq("org.scalatest" %% "scalatest" % scalaTestVer % Test),
  test in assembly := {}
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(name := "controller")
  .aggregate(
    model,
    remote,
    broadlink,
    store,
    fileStore,
    remoteControl,
    extruderConfigSource,
    `macro`,
    activity,
    hs100Switch,
    poller,
    pollingSwitch,
    switch,
    sonosClientSubmodule,
    virtualSwitch,
    stats
  )

lazy val api = (project in file("modules/api"))
  .settings(commonSettings)
  .settings(
    name := "controller-api",
    libraryDependencies ++= Seq(
      "extruder"       %% "extruder-cats-effect" % extruderVer,
      "extruder"       %% "extruder-circe"       % extruderVer,
      "extruder"       %% "extruder-refined"     % extruderVer,
      "extruder"       %% "extruder-typesafe"    % extruderVer,
      "ch.qos.logback" % "logback-classic"       % "1.2.3",
      "org.http4s"     %% "http4s-blaze-server"  % http4sVer,
      "org.http4s"     %% "http4s-circe"         % http4sVer,
      "org.http4s"     %% "http4s-core"          % http4sVer,
      "org.http4s"     %% "http4s-dsl"           % http4sVer
    )
  )
  .dependsOn(
    hs100Switch,
    broadlink,
    fileStore,
    remoteControl,
    extruderConfigSource,
    `macro`,
    activity,
    sonos,
    virtualSwitch,
    stats
  )

lazy val catsEffect = (project in file("modules/cats-effect"))
  .settings(commonSettings)
  .settings(
    name := "controller-cats-effect",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"   % catsVer,
      "org.typelevel" %% "cats-effect" % catsEffectVer
    )
  )

lazy val model = (project in file("modules/model"))
  .settings(commonSettings)
  .settings(
    name := "controller-model",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVer,
      "eu.timepit"    %% "refined"   % refinedVer,
      "org.typelevel" %% "kittens"   % kittensVer
    )
  )

lazy val configSource = (project in file("modules/config-source"))
  .settings(commonSettings)
  .settings(name := "controller-config-source")
  .dependsOn(model)

lazy val extruder = (project in file("modules/extruder"))
  .settings(commonSettings)
  .settings(
    name := "controller-extruder",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-parser"         % circeVer,
      "extruder" %% "extruder-cats-effect" % extruderVer,
      "extruder" %% "extruder-circe"       % extruderVer,
      "extruder" %% "extruder-refined"     % extruderVer,
      "extruder" %% "extruder-typesafe"    % extruderVer
    )
  )
  .dependsOn(catsEffect, poller)

lazy val extruderConfigSource = (project in file("modules/extruder-config-source"))
  .settings(commonSettings)
  .settings(name := "controller-extruder-config-source")
  .dependsOn(configSource, extruder)

lazy val remote = (project in file("modules/remote"))
  .settings(commonSettings)
  .settings(name := "controller-remote")
  .dependsOn(model)

lazy val broadlink = (project in file("modules/broadlink"))
  .settings(commonSettings)
  .settings(
    name := "controller-broadlink",
    libraryDependencies ++= Seq(
      "com.github.mob41.blapi" % "broadlink-java-api" % "1.0.1",
      "javax.xml.bind"         % "jaxb-api"           % "2.3.0",
      "eu.timepit"             %% "refined"           % refinedVer
    )
  )
  .dependsOn(remote, switch, remoteControl, catsEffect, pollingSwitch)

lazy val switch = (project in file("modules/switch"))
  .settings(commonSettings)
  .settings(
    name := "controller-switch",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVer,
      "eu.timepit"    %% "refined"   % refinedVer,
      "org.typelevel" %% "kittens"   % kittensVer
    )
  )
  .dependsOn(model)

lazy val pollingSwitch = (project in file("modules/polling-switch"))
  .settings(commonSettings)
  .settings(name := "controller-polling-switch")
  .dependsOn(switch, poller)

lazy val hs100Switch = (project in file("modules/hs100-switch"))
  .settings(commonSettings)
  .settings(
    name := "controller-hs100-switch",
    libraryDependencies ++= Seq(
      "io.circe"   %% "circe-core"   % circeVer,
      "io.circe"   %% "circe-parser" % circeVer,
      "eu.timepit" %% "refined"      % refinedVer
    )
  )
  .dependsOn(catsEffect, pollingSwitch)

lazy val virtualSwitch = (project in file("modules/virtual-switch"))
  .settings(commonSettings)
  .settings(name := "controller-virtual-switch", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(store, configSource, catsEffect, pollingSwitch, remoteControl)

lazy val store = (project in file("modules/store"))
  .settings(commonSettings)
  .settings(name := "controller-store", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(model)

lazy val fileStore = (project in file("modules/file-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-file-store",
    libraryDependencies ++= Seq(
      "commons-io" % "commons-io"        % "2.6",
      "io.circe"   %% "circe-parser"     % circeVer,
      "extruder"   %% "extruder-circe"   % extruderVer,
      "extruder"   %% "extruder-refined" % extruderVer,
      "eu.timepit" %% "refined"          % refinedVer
    )
  )
  .dependsOn(catsEffect, store, poller)

lazy val remoteControl = (project in file("modules/remote-control"))
  .settings(commonSettings)
  .settings(
    name := "controller-remote-control",
    libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer, "org.typelevel" %% "cats-core" % catsVer)
  )
  .dependsOn(remote, store)

lazy val `macro` = (project in file("modules/macro"))
  .settings(commonSettings)
  .settings(name := "controller-macro", libraryDependencies ++= Seq("org.typelevel" %% "cats-effect" % catsEffectVer))
  .dependsOn(remoteControl, switch, store, configSource)

lazy val activity = (project in file("modules/activity"))
  .settings(commonSettings)
  .settings(name := "controller-activity")
  .dependsOn(`macro`)

lazy val poller = (project in file("modules/poller"))
  .settings(commonSettings)
  .settings(
    name := "controller-poller",
    libraryDependencies ++= Seq("co.fs2" %% "fs2-core" % fs2Ver, "eu.timepit" %% "refined" % refinedVer)
  )
  .dependsOn(catsEffect)

lazy val stats = (project in file("modules/stats"))
  .settings(commonSettings)
  .settings(
    name := "controller-stats",
    libraryDependencies ++= Seq("co.fs2" %% "fs2-core" % fs2Ver, "eu.timepit" %% "refined" % refinedVer)
  )
  .dependsOn(catsEffect, remoteControl, activity, `macro`, switch, configSource)

lazy val prometheusStats = (project in file("modules/prometheus-stats"))
  .settings(commonSettings)
  .settings(
    name := "controller-prometheus-stats",
    libraryDependencies ++= Seq(
      "io.prometheus" % "simpleclient" % "0.6.0",
      "org.http4s"    %% "http4s-core" % http4sVer,
      "org.http4s"    %% "http4s-dsl"  % http4sVer
    )
  )
  .dependsOn(stats)

lazy val sonos = (project in file("modules/sonos"))
  .settings(commonSettings)
  .settings(name := "controller-sonos")
  .dependsOn(sonosClientSubmodule, remoteControl, switch, configSource, poller)

lazy val sonosClientSubmodule = (project in file("submodules/sonos-controller"))
  .settings(commonSettings)
  .settings(
    organization := "com.vmichalak",
    name := "sonos-controller",
    libraryDependencies ++= Seq(
      "com.squareup.okhttp3" % "okhttp"                         % "3.9.0",
      "org.apache.commons"   % "commons-text"                   % "1.1",
      "junit"                % "junit"                          % "4.11" % Test,
      "org.mockito"          % "mockito-core"                   % "1.10.8" % Test,
      "org.powermock"        % "powermock-mockito-release-full" % "1.6.4" % Test,
      "org.slf4j"            % "slf4j-api"                      % "1.7.10" % Test
    )
  )
  .dependsOn(ssdpClientSubmodule)

lazy val ssdpClientSubmodule = (project in file("submodules/sonos-controller/lib/ssdp-client"))
  .settings(commonSettings)
  .settings(organization := "com.vmichalak", name := "ssdp-client")
