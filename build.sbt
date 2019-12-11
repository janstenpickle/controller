import com.typesafe.sbt.packager.docker.DockerPermissionStrategy

val catsVer = "2.0.0"
val catsEffectVer = "2.0.0"
val circeVer = "0.12.1"
val collectionCompatVer = "2.1.2"
val extruderVer = "0.11.0"
val fs2Ver = "2.1.0"
val http4sVer = "0.21.0-M6"
val kittensVer = "2.0.0"
val log4catsVer = "1.0.1"
val natchezVer = "0.0.10"
val prometheusVer = "0.8.0"
val refinedVer = "0.9.10"
val scalaCacheVer = "0.28.0"
val scalaCheckVer = "1.13.5"
val scalaCheckShapelessVer = "1.1.8"
val scalaTestVer = "3.0.8"

val commonSettings = Seq(
  organization := "io.janstenpickle",
  scalaVersion := "2.12.10",
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-feature",
    "-deprecation:false",
    "-Xcheckinit",
    "-Xlint:-nullary-unit",
    "-Ywarn-numeric-widen",
    "-Ywarn-dead-code",
    "-language:_",
    "-encoding",
    "UTF-8"
  ),
  addCompilerPlugin(("org.typelevel" %% "kind-projector" % "0.11.0").cross(CrossVersion.patch)),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
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
  resolvers += Resolver.jcenterRepo,
  libraryDependencies ++= Seq("org.scalatest" %% "scalatest" % scalaTestVer % Test),
  packExcludeJars := Seq("slf4j-jdk14.*\\.jar"),
  assemblyExcludedJars in assembly := {
    val cp = (fullClasspath in assembly).value
    cp.filter {
      _.data.getName.contains("slf4j-jdk14")
    }
  },
  publishArtifact in packageDoc := false
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(name := "controller")
  .aggregate(
    api,
    model,
    components,
    remote,
    broadlink,
    store,
    remoteControl,
    extruderConfigSource,
    `macro`,
    activity,
    tplink,
    poller,
    pollingSwitch,
    kodi,
    sonos,
    switch,
    sonosClientSubmodule,
    virtualSwitch,
    stats,
    prometheusStats,
    gitRemoteStore
  )

lazy val api = (project in file("modules/api"))
  .settings(commonSettings)
  .settings(
    name := "controller-api",
    packageName in Docker := "controller",
    dockerRepository := Some("janstenpickle"),
    dockerUpdateLatest := true,
    dockerBaseImage := "openjdk:13",
    dockerExposedPorts += 8090,
    daemonUserUid in Docker := Some("9000"),
    javaOptions in Universal ++= Seq("-Djava.net.preferIPv4Stack=true"),
    dockerPermissionStrategy := DockerPermissionStrategy.Run,
    libraryDependencies ++= Seq(
      "eu.timepit"        %% "refined-cats"              % refinedVer,
      "io.extruder"       %% "extruder-cats-effect"      % extruderVer,
      "io.extruder"       %% "extruder-circe"            % extruderVer,
      "io.extruder"       %% "extruder-refined"          % extruderVer,
      "io.extruder"       %% "extruder-typesafe"         % extruderVer,
      "ch.qos.logback"    % "logback-classic"            % "1.2.3",
      "io.chrisdavenport" %% "log4cats-slf4j"            % log4catsVer,
      "org.http4s"        %% "http4s-blaze-client"       % http4sVer,
      "org.http4s"        %% "http4s-blaze-server"       % http4sVer,
      "org.http4s"        %% "http4s-circe"              % http4sVer,
      "org.http4s"        %% "http4s-core"               % http4sVer,
      "org.http4s"        %% "http4s-dsl"                % http4sVer,
      "org.http4s"        %% "http4s-prometheus-metrics" % http4sVer,
      "org.typelevel"     %% "cats-mtl-core"             % "0.7.0",
      "org.tpolecat"      %% "natchez-jaeger"            % natchezVer
    ),
    mappings in Universal := {
      val universalMappings = (mappings in Universal).value

      universalMappings.filter {
        case (_, name) => !name.contains("slf4j-jdk14")
      }
    }
  )
  .dependsOn(
    arrow,
    tplink,
    broadlink,
    tracedStore,
    remoteControl,
    extruderConfigSource,
    gitRemoteStore,
    `macro`,
    activity,
    sonos,
    kodi,
    virtualSwitch,
    multiSwitch,
    stats,
    prometheusStats,
    prometheusTrace,
    trace
  )
  .enablePlugins(UniversalPlugin, JavaAppPackaging, DockerPlugin, PackPlugin)

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

lazy val components = (project in file("modules/components"))
  .settings(commonSettings)
  .settings(name := "controller-components")
  .dependsOn(model, configSource, remoteControl, switch, dynamicDiscovery)

lazy val configSource = (project in file("modules/config-source"))
  .settings(commonSettings)
  .settings(
    name := "controller-config-source",
    libraryDependencies ++= Seq("eu.timepit" %% "refined-cats" % refinedVer)
  )
  .dependsOn(model)

lazy val tracedConfigSource = (project in file("modules/trace-config-source"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace-config-source",
    libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer)
  )
  .dependsOn(configSource)

lazy val extruder = (project in file("modules/extruder"))
  .settings(commonSettings)
  .settings(
    name := "controller-extruder",
    libraryDependencies ++= Seq(
      "commons-io"  % "commons-io"            % "2.6",
      "io.circe"    %% "circe-parser"         % circeVer,
      "io.extruder" %% "extruder-cats-effect" % extruderVer,
      "io.extruder" %% "extruder-circe"       % extruderVer,
      "io.extruder" %% "extruder-refined"     % extruderVer,
      "io.extruder" %% "extruder-typesafe"    % extruderVer
    )
  )
  .dependsOn(poller)

lazy val extruderConfigSource = (project in file("modules/extruder-config-source"))
  .settings(commonSettings)
  .settings(
    name := "controller-extruder-config-source",
    libraryDependencies ++= Seq(
      "io.chrisdavenport"      %% "log4cats-slf4j"          % log4catsVer,
      "org.scala-lang.modules" %% "scala-collection-compat" % collectionCompatVer
    )
  )
  .dependsOn(configSource, extruder, tracedConfigSource)

lazy val remote = (project in file("modules/remote"))
  .settings(commonSettings)
  .settings(name := "controller-remote")
  .dependsOn(model)

lazy val arrow = (project in file("modules/arrow"))
  .settings(commonSettings)
  .settings(name := "controller-arrow", libraryDependencies ++= Seq("org.typelevel" %% "cats-core" % catsVer))
  .dependsOn(model)

lazy val tracedRemote = (project in file("modules/trace-remote"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace-remote",
    libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer)
  )
  .dependsOn(remote)

lazy val broadlink = (project in file("modules/broadlink"))
  .settings(commonSettings)
  .settings(
    name := "controller-broadlink",
    libraryDependencies ++= Seq(
      "javax.xml.bind" % "jaxb-api"     % "2.3.0",
      "eu.timepit"     %% "refined"     % refinedVer,
      "org.typelevel"  %% "cats-effect" % catsEffectVer
    )
  )
  .dependsOn(
    cache,
    broadlinkApiSubmodule,
    components,
    remote,
    tracedRemote,
    switch,
    tracedSwitch,
    remoteControl,
    pollingSwitch,
    dynamicDiscovery
  )

lazy val switch = (project in file("modules/switch"))
  .settings(commonSettings)
  .settings(
    name := "controller-switch",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"    % catsVer,
      "eu.timepit"    %% "refined"      % refinedVer,
      "org.typelevel" %% "kittens"      % kittensVer,
      "org.tpolecat"  %% "natchez-core" % natchezVer
    )
  )
  .dependsOn(model)

lazy val pollingSwitch = (project in file("modules/polling-switch"))
  .settings(commonSettings)
  .settings(name := "controller-polling-switch")
  .dependsOn(switch, poller)

lazy val tplink = (project in file("modules/tplink"))
  .settings(commonSettings)
  .settings(
    name := "controller-tplink",
    libraryDependencies ++= Seq(
      "io.circe"      %% "circe-core"   % circeVer,
      "io.circe"      %% "circe-parser" % circeVer,
      "eu.timepit"    %% "refined"      % refinedVer,
      "org.typelevel" %% "cats-effect"  % catsEffectVer
    )
  )
  .dependsOn(components, dynamicDiscovery, pollingSwitch, tracedSwitch, tracedConfigSource, tracedRemote)

lazy val virtualSwitch = (project in file("modules/virtual-switch"))
  .settings(commonSettings)
  .settings(name := "controller-virtual-switch", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(store, configSource, pollingSwitch, remoteControl)

lazy val multiSwitch = (project in file("modules/multi-switch"))
  .settings(commonSettings)
  .settings(name := "controller-multi-switch", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(configSource, switch, tracedSwitch)

lazy val tracedSwitch = (project in file("modules/trace-switch"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace-switch",
    libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer)
  )
  .dependsOn(switch)

lazy val store = (project in file("modules/store"))
  .settings(commonSettings)
  .settings(name := "controller-store", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(model, configSource)

lazy val tracedStore = (project in file("modules/trace-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace-store",
    libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer)
  )
  .dependsOn(store)

lazy val remoteControl = (project in file("modules/remote-control"))
  .settings(commonSettings)
  .settings(
    name := "controller-remote-control",
    libraryDependencies ++= Seq(
      "eu.timepit"    %% "refined"      % refinedVer,
      "org.typelevel" %% "cats-core"    % catsVer,
      "org.tpolecat"  %% "natchez-core" % natchezVer
    )
  )
  .dependsOn(remote, store)

lazy val `macro` = (project in file("modules/macro"))
  .settings(commonSettings)
  .settings(
    name := "controller-macro",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"  % catsEffectVer,
      "org.tpolecat"  %% "natchez-core" % natchezVer
    )
  )
  .dependsOn(remoteControl, switch, store, configSource)

lazy val activity = (project in file("modules/activity"))
  .settings(commonSettings)
  .settings(name := "controller-activity", libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer))
  .dependsOn(`macro`)

lazy val poller = (project in file("modules/poller"))
  .settings(commonSettings)
  .settings(
    name := "controller-poller",
    libraryDependencies ++= Seq(
      "co.fs2"                 %% "fs2-core"                % fs2Ver,
      "eu.timepit"             %% "refined"                 % refinedVer,
      "org.tpolecat"           %% "natchez-core"            % natchezVer,
      "org.typelevel"          %% "cats-effect"             % catsEffectVer,
      "org.scala-lang.modules" %% "scala-collection-compat" % collectionCompatVer
    )
  )
  .dependsOn(arrow)

lazy val stats = (project in file("modules/stats"))
  .settings(commonSettings)
  .settings(
    name := "controller-stats",
    libraryDependencies ++= Seq(
      "co.fs2"                 %% "fs2-core"                % fs2Ver,
      "eu.timepit"             %% "refined"                 % refinedVer,
      "org.scala-lang.modules" %% "scala-collection-compat" % collectionCompatVer
    )
  )
  .dependsOn(remoteControl, activity, `macro`, switch, configSource)

lazy val prometheusStats = (project in file("modules/prometheus-stats"))
  .settings(commonSettings)
  .settings(
    name := "controller-prometheus-stats",
    libraryDependencies ++= Seq(
      "io.prometheus" % "simpleclient"        % prometheusVer,
      "io.prometheus" % "simpleclient_common" % prometheusVer,
      "org.http4s"    %% "http4s-core"        % http4sVer,
      "org.http4s"    %% "http4s-dsl"         % http4sVer
    )
  )
  .dependsOn(stats)

lazy val prometheusTrace = (project in file("modules/prometheus-trace"))
  .settings(commonSettings)
  .settings(
    name := "controller-prometheus-trace",
    libraryDependencies ++= Seq(
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer,
      "io.prometheus"     % "simpleclient"    % prometheusVer,
      "org.tpolecat"      %% "natchez-core"   % natchezVer,
    )
  )

lazy val cache = (project in file("modules/cache"))
  .settings(commonSettings)
  .settings(
    name := "controller-cache",
    libraryDependencies ++= Seq(
      "com.github.cb372" %% "scalacache-cache2k"     % scalaCacheVer,
      "com.github.cb372" %% "scalacache-cats-effect" % scalaCacheVer,
      "io.prometheus"    % "simpleclient_hotspot"    % prometheusVer,
      "org.typelevel"    %% "cats-effect"            % catsEffectVer
    )
  )

lazy val gitRemoteStore = (project in file("modules/git-remote-command-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-git-remote-command-store",
    libraryDependencies ++= Seq(
      "commons-io"        % "commons-io"      % "2.6",
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer,
      "org.typelevel"     %% "cats-effect"    % catsEffectVer,
      "org.typelevel"     %% "kittens"        % kittensVer,
      "org.tpolecat"      %% "natchez-core"   % natchezVer,
      "org.http4s"        %% "http4s-dsl"     % http4sVer,
      "org.http4s"        %% "http4s-client"  % http4sVer,
      "org.http4s"        %% "http4s-circe"   % http4sVer,
      "io.circe"          %% "circe-generic"  % circeVer,
      "eu.timepit"        %% "refined-cats"   % refinedVer
    )
  )
  .dependsOn(poller, store)

lazy val sonos = (project in file("modules/sonos"))
  .settings(commonSettings)
  .settings(
    name := "controller-sonos",
    libraryDependencies ++= Seq("io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer)
  )
  .dependsOn(
    components,
    sonosClientSubmodule,
    cache,
    remoteControl,
    tracedRemote,
    switch,
    tracedSwitch,
    configSource,
    tracedConfigSource,
    dynamicDiscovery
  )

lazy val kodi = (project in file("modules/kodi"))
  .settings(commonSettings)
  .settings(
    name := "controller-kodi",
    libraryDependencies ++= Seq(
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer,
      "org.http4s"        %% "http4s-dsl"     % http4sVer,
      "org.http4s"        %% "http4s-client"  % http4sVer,
      "org.http4s"        %% "http4s-circe"   % http4sVer,
      "io.circe"          %% "circe-generic"  % circeVer,
      "org.jmdns"         % "jmdns"           % "3.5.5"
    )
  )
  .dependsOn(
    components,
    remoteControl,
    cache,
    tracedRemote,
    switch,
    pollingSwitch,
    tracedSwitch,
    configSource,
    tracedConfigSource,
    dynamicDiscovery
  )

lazy val dynamicDiscovery = (project in file("modules/dynamic-discovery"))
  .settings(commonSettings)
  .settings(
    name := "controller-dynamic-discovery",
    libraryDependencies ++= Seq("org.typelevel" %% "kittens" % kittensVer)
  )
  .dependsOn(poller)

lazy val trace = (project in file("modules/trace"))
  .settings(commonSettings)
  .settings(name := "controller-trace", libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer))

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

lazy val ssdpClientSubmodule = (project in file("submodules/ssdp-client"))
  .settings(commonSettings)
  .settings(organization := "com.vmichalak", name := "ssdp-client")

lazy val broadlinkApiSubmodule = (project in file("submodules/broadlink-java-api"))
  .settings(commonSettings)
  .settings(
    organization := "com.github.mob41.blapi",
    name := "broadlink-java-api",
    libraryDependencies ++= Seq(
      "javax.xml.bind" % "jaxb-api"     % "2.3.0",
      "org.slf4j"      % "slf4j-api"    % "1.7.22",
      "junit"          % "junit"        % "4.12" % Test,
      "org.slf4j"      % "slf4j-simple" % "1.7.22" % Test
    )
  )
