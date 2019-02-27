import sbt.Keys.libraryDependencies
import sbt.url

val catsVer = "1.6.0"
val catsEffectVer = "1.2.0"
val circeVer = "0.11.1"
val extruderVer = "0.9.3-7-41cfb983-dirty-SNAPSHOT"
val http4sVer = "0.20.0-M6"
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
  addCompilerPlugin(("io.tryp"        % "splain"         % "0.4.0").cross(CrossVersion.patch)),
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
  libraryDependencies ++= Seq("org.scalatest" %% "scalatest" % scalaTestVer % Test)
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(name := "controller")
  .aggregate(
    model,
    remote,
    rm2Remote,
    store,
    fileStore,
    remoteControl,
    extruderConfigSource,
    `macro`,
    activity,
    hs100Switch,
    poller,
    pollingSwitch,
    switch
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
  .dependsOn(hs100Switch, rm2Remote, fileStore, remoteControl, extruderConfigSource, `macro`, activity)

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
    libraryDependencies ++= Seq("org.typelevel" %% "cats-core" % catsVer, "eu.timepit" %% "refined" % refinedVer)
  )

lazy val configSource = (project in file("modules/config-source"))
  .settings(commonSettings)
  .settings(name := "controller-config-source")
  .dependsOn(model)

lazy val extruderConfigSource = (project in file("modules/extruder-config-source"))
  .settings(commonSettings)
  .settings(
    name := "controller-extruder-config-source",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-parser"         % circeVer,
      "extruder" %% "extruder-cats-effect" % extruderVer,
      "extruder" %% "extruder-circe"       % extruderVer,
      "extruder" %% "extruder-circe-yaml"  % extruderVer,
      "extruder" %% "extruder-refined"     % extruderVer,
      "extruder" %% "extruder-typesafe"    % extruderVer
    )
  )
  .dependsOn(catsEffect, configSource, poller)

lazy val remote = (project in file("modules/remote"))
  .settings(commonSettings)
  .settings(name := "controller-remote")
  .dependsOn(model)

lazy val rm2Remote = (project in file("modules/rm2-remote"))
  .settings(commonSettings)
  .settings(
    name := "controller-rm2-remote",
    libraryDependencies ++= Seq(
      "com.github.mob41.blapi" % "broadlink-java-api" % "1.0.1",
      "javax.xml.bind"         % "jaxb-api"           % "2.3.0",
      "eu.timepit"             %% "refined"           % refinedVer
    )
  )
  .dependsOn(remote, catsEffect)

lazy val switch = (project in file("modules/switch"))
  .settings(commonSettings)
  .settings(
    name := "controller-switch",
    libraryDependencies ++= Seq("org.typelevel" %% "cats-core" % catsVer, "eu.timepit" %% "refined" % refinedVer)
  )

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

lazy val store = (project in file("modules/store"))
  .settings(commonSettings)
  .settings(name := "controller-store", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(model)

lazy val fileStore = (project in file("modules/file-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-file-store",
    libraryDependencies ++= Seq(
      "commons-io" % "commons-io"           % "2.6",
      "extruder"   %% "extruder-circe-yaml" % extruderVer,
      "extruder"   %% "extruder-refined"    % extruderVer,
      "eu.timepit" %% "refined"             % refinedVer
    )
  )
  .dependsOn(catsEffect, store)

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
  .settings(name := "controller-poller", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(catsEffect)
