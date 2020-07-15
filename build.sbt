val catsVer = "2.1.1"
val catsEffectVer = "2.1.3"
val catsMtlVer = "0.7.1"
val circeVer = "0.13.0"
val collectionCompatVer = "2.1.6"
val extruderVer = "0.11.0"
val fs2Ver = "2.3.0"
val http4sVer = "0.21.4"
val kittensVer = "2.1.0"
val jmdnsVer = "3.5.5"
val log4catsVer = "1.1.1"
val maprefVer = "0.1.1"
val prometheusVer = "0.9.0"
val refinedVer = "0.9.14"
val scalaCacheVer = "0.28.0"
val scalaCheckVer = "1.13.5"
val scalaCheckShapelessVer = "1.1.8"
val scalaTestVer = "3.1.2"
val trace4catsVer = "0.2.0+6-29c0a493"

val commonSettings = Seq(
  organization := "io.janstenpickle",
  scalaVersion := "2.13.2",
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
  publishArtifact in packageDoc := false,
  resolvers += Resolver.sonatypeRepo("releases")
)

val graalSettings = Seq(
  graalVMNativeImageOptions ++= Seq(
    "--verbose",
    "--no-server",
    "--no-fallback",
    "--enable-http",
    "--enable-https",
    "--enable-all-security-services",
    "--report-unsupported-elements-at-runtime",
    "--allow-incomplete-classpath",
    "-Djava.net.preferIPv4Stack=true",
    "-H:IncludeResources='.*'",
    "-H:+ReportExceptionStackTraces",
    "-H:+ReportUnsupportedElementsAtRuntime",
    "-H:+TraceClassInitialization",
    "-H:+PrintClassInitialization",
    "-H:+RemoveSaturatedTypeFlows",
    "-H:+StackTrace",
    "-H:+JNI",
    "-H:-SpawnIsolates",
    "-H:-UseServiceLoaderFeature",
    "-H:ConfigurationFileDirectories=../../native-image/",
    "--install-exit-handlers",
    "--initialize-at-build-time=scala.runtime.Statics$VM",
    "--initialize-at-build-time=scala.Symbol$",
    "--initialize-at-build-time=ch.qos.logback",
    "--initialize-at-build-time=org.slf4j.LoggerFactory",
    "--initialize-at-run-time=org.slf4j.impl.StaticLoggerBinder",
    "--initialize-at-run-time=io.netty.channel.ChannelHandlerMask",
    "--initialize-at-build-time=io.grpc.okhttp",
    "--initialize-at-build-time=io.grpc.okhttp.OkHttpChannelProvider",
    "--initialize-at-build-time=io.grpc.internal.DnsNameResolverProvider"
  )
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(name := "controller")
  .aggregate(
    advertiser,
    api,
    `all-in-one`,
    coordinator,
    model,
    errors,
    components,
    eventCommands,
    remote,
    broadlink,
    `broadlink-server`,
    remoteControl,
    circeConfigSource,
    `macro`,
    context,
    activity,
    tplink,
    `tplink-server`,
    poller,
    pollingSwitch,
    kodi,
    `kodi-server`,
    sonos,
    `sonos-server`,
    switch,
    sonosClientSubmodule,
    virtualSwitch,
    stats,
    schedule,
    cronScheduler,
    prometheusStats,
    gitRemoteStore,
    homekit,
    mqttClient,
    mqttEvents,
    websocketEvents,
    deconzBridge,
    `deconz-server`,
    hapJavaSubmodule,
    eventDrivenSwitches,
    eventDrivenRemoteControls,
    eventDrivenActivity,
    eventDrivenDeviceRename,
    server,
    homekitServer
  )

lazy val `all-in-one` = (project in file("modules/all-in-one"))
  .settings(commonSettings)
  .settings(graalSettings)
  .settings(
    name := "controller-all-in-one",
    packageName in Docker := "controller",
    dockerRepository := Some("janstenpickle"),
    dockerUpdateLatest := true,
    dockerBaseImage := "openjdk:13",
    dockerExposedPorts += 8090,
    daemonUserUid in Docker := Some("9000"),
    javaOptions in Universal ++= Seq("-Djava.net.preferIPv4Stack=true"),
//    dockerPermissionStrategy := DockerPermissionStrategy.Run,
    libraryDependencies ++= Seq(
      "eu.timepit"        %% "refined-cats"              % refinedVer,
      "io.extruder"       %% "extruder-cats-effect"      % extruderVer,
      "io.extruder"       %% "extruder-circe"            % extruderVer,
      "io.extruder"       %% "extruder-refined"          % extruderVer,
      "io.extruder"       %% "extruder-typesafe"         % extruderVer,
      "ch.qos.logback"    % "logback-classic"            % "1.2.3",
      "io.chrisdavenport" %% "log4cats-slf4j"            % log4catsVer,
      "org.http4s"        %% "http4s-jdk-http-client"    % "0.3.0",
      "org.http4s"        %% "http4s-blaze-server"       % http4sVer,
      "org.http4s"        %% "http4s-circe"              % http4sVer,
      "org.http4s"        %% "http4s-core"               % http4sVer,
      "org.http4s"        %% "http4s-dsl"                % http4sVer,
      "org.http4s"        %% "http4s-prometheus-metrics" % http4sVer,
      "org.typelevel"     %% "cats-mtl-core"             % catsMtlVer,
      "io.janstenpickle"  %% "trace4cats-inject"         % trace4catsVer,
      "io.janstenpickle"  %% "trace4cats-avro-exporter"  % trace4catsVer
    ),
    mappings in Universal := {
      val universalMappings = (mappings in Universal).value

      universalMappings.filter {
        case (_, name) => !name.contains("slf4j-jdk14")
      }
    }
  )
  .dependsOn(
    api,
    advertiser,
    arrow,
    tplink,
    broadlink,
    eventCommands,
    remoteControl,
    remoteConfig,
    remoteStore,
    circeConfigSource,
    gitRemoteStore,
    `macro`,
    macroConfig,
    context,
    activity,
    activityConfig,
    sonos,
    kodi,
    switchStore,
    switchConfig,
    virtualSwitch,
    multiSwitch,
    stats,
    prometheusStats,
    prometheusTrace,
    trace,
    kafkaEvents,
    cronScheduler,
    deconzBridge,
    eventDrivenComponents,
    websocketEvents,
    discoveryConfig,
    server,
    websocketClientEvents
  )
  .enablePlugins(UniversalPlugin, JavaAppPackaging, DockerPlugin, PackPlugin, GraalVMNativeImagePlugin)

lazy val api = (project in file("modules/api"))
  .settings(commonSettings)
  .settings(
    name := "controller-api",
    libraryDependencies ++= Seq(
      "eu.timepit"        %% "refined-cats"         % refinedVer,
      "io.extruder"       %% "extruder-cats-effect" % extruderVer,
      "io.chrisdavenport" %% "log4cats-slf4j"       % log4catsVer,
      "org.http4s"        %% "http4s-server"        % http4sVer,
      "org.http4s"        %% "http4s-circe"         % http4sVer,
      "org.http4s"        %% "http4s-core"          % http4sVer,
      "org.http4s"        %% "http4s-dsl"           % http4sVer,
      "org.typelevel"     %% "cats-mtl-core"        % catsMtlVer
    )
  )
  .dependsOn(
    arrow,
    http4s,
    trace,
    events,
    remoteControl,
    switch,
    `macro`,
    context,
    activity,
    configSource,
    model,
    dynamicDiscovery,
    schedule
  )

lazy val pluginApi = (project in file("modules/plugins/api"))
  .settings(commonSettings)
  .settings(name := "controller-plugin-api")
  .dependsOn(api, components)

lazy val coordinator = (project in file("modules/coordinator"))
  .settings(commonSettings)
  .settings(graalSettings)
  .settings(
    name := "controller-coordinator",
    libraryDependencies ++= Seq(
      "eu.timepit"        %% "refined-cats"              % refinedVer,
      "io.extruder"       %% "extruder-cats-effect"      % extruderVer,
      "io.extruder"       %% "extruder-circe"            % extruderVer,
      "io.extruder"       %% "extruder-refined"          % extruderVer,
      "io.extruder"       %% "extruder-typesafe"         % extruderVer,
      "ch.qos.logback"    % "logback-classic"            % "1.2.3",
      "io.chrisdavenport" %% "log4cats-slf4j"            % log4catsVer,
      "org.http4s"        %% "http4s-jdk-http-client"    % "0.3.0",
      "org.http4s"        %% "http4s-blaze-server"       % http4sVer,
      "org.http4s"        %% "http4s-circe"              % http4sVer,
      "org.http4s"        %% "http4s-core"               % http4sVer,
      "org.http4s"        %% "http4s-dsl"                % http4sVer,
      "org.http4s"        %% "http4s-prometheus-metrics" % http4sVer,
      "org.typelevel"     %% "cats-mtl-core"             % catsMtlVer,
      "io.janstenpickle"  %% "trace4cats-inject"         % trace4catsVer,
      "io.janstenpickle"  %% "trace4cats-avro-exporter"  % trace4catsVer
    )
  )
  .dependsOn(
    api,
    advertiser,
    server,
    websocketEvents,
    trace,
    prometheusTrace,
    eventCommands,
    remoteControl,
    remoteConfig,
    remoteStore,
    switchStore,
    switchConfig,
    virtualSwitch,
    multiSwitch,
    stats,
    prometheusStats,
    prometheusTrace,
    trace,
    circeConfigSource,
    gitRemoteStore,
    `macro`,
    macroConfig,
    context,
    activity,
    activityConfig,
    cronScheduler,
    eventDrivenComponents
  )
  .enablePlugins(GraalVMNativeImagePlugin)

lazy val server = (project in file("modules/server"))
  .settings(commonSettings)
  .settings(
    name := "controller-server",
    libraryDependencies ++= Seq(
      "eu.timepit"        %% "refined-cats"              % refinedVer,
      "io.extruder"       %% "extruder-cats-effect"      % extruderVer,
      "io.extruder"       %% "extruder-circe"            % extruderVer,
      "io.extruder"       %% "extruder-refined"          % extruderVer,
      "io.extruder"       %% "extruder-typesafe"         % extruderVer,
      "io.chrisdavenport" %% "log4cats-slf4j"            % log4catsVer,
      "org.http4s"        %% "http4s-blaze-server"       % http4sVer,
      "org.http4s"        %% "http4s-circe"              % http4sVer,
      "org.http4s"        %% "http4s-core"               % http4sVer,
      "org.http4s"        %% "http4s-dsl"                % http4sVer,
      "org.http4s"        %% "http4s-prometheus-metrics" % http4sVer,
      "org.typelevel"     %% "cats-mtl-core"             % catsMtlVer
    )
  )
  .dependsOn(advertiser, poller, arrow, http4s, websocketEvents, components, trace)

lazy val http4s = (project in file("modules/http4s"))
  .settings(commonSettings)
  .settings(
    name := "controller-http4s",
    libraryDependencies ++= Seq(
      "org.http4s"        %% "http4s-client"     % http4sVer,
      "org.http4s"        %% "http4s-core"       % http4sVer,
      "org.http4s"        %% "http4s-dsl"        % http4sVer,
      "io.chrisdavenport" %% "log4cats-slf4j"    % log4catsVer,
      "org.typelevel"     %% "cats-mtl-core"     % catsMtlVer,
      "io.janstenpickle"  %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(errors)

lazy val advertiser = (project in file("modules/advertiser"))
  .settings(commonSettings)
  .settings(
    name := "controller-advertiser",
    libraryDependencies ++= Seq(
      "co.fs2"            %% "fs2-core"       % fs2Ver,
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer,
      "org.typelevel"     %% "cats-core"      % catsVer,
      "org.typelevel"     %% "cats-effect"    % catsEffectVer,
      "eu.timepit"        %% "refined"        % refinedVer,
      "org.jmdns"         % "jmdns"           % jmdnsVer
    )
  )

lazy val model = (project in file("modules/model"))
  .settings(commonSettings)
  .settings(
    name := "controller-model",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"            % catsVer,
      "eu.timepit"    %% "refined"              % refinedVer,
      "org.typelevel" %% "kittens"              % kittensVer,
      "io.circe"      %% "circe-parser"         % circeVer,
      "io.circe"      %% "circe-generic-extras" % circeVer,
      "io.circe"      %% "circe-refined"        % circeVer
    )
  )

lazy val errors = (project in file("modules/errors"))
  .settings(commonSettings)
  .settings(name := "controller-errors")

lazy val components = (project in file("modules/components/components"))
  .settings(commonSettings)
  .settings(name := "controller-components")
  .dependsOn(model, configSource, remoteControl, schedule, switch, dynamicDiscovery)

lazy val eventDrivenComponents = (project in file("modules/components/event-driven-components"))
  .settings(commonSettings)
  .settings(name := "controller-event-driven-components")
  .dependsOn(
    components,
    events,
    eventDrivenRemoteControls,
    eventDrivenSwitches,
    eventDrivenActivity,
    eventDrivenDeviceRename
  )

lazy val configSource = (project in file("modules/config/config-source"))
  .settings(commonSettings)
  .settings(
    name := "controller-config-source",
    libraryDependencies ++= Seq("eu.timepit" %% "refined-cats" % refinedVer)
  )
  .dependsOn(model)

lazy val tracedConfigSource = (project in file("modules/config/trace-config-source"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace-config-source",
    libraryDependencies ++= Seq("io.janstenpickle" %% "trace4cats-inject" % trace4catsVer)
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

lazy val circeConfigSource = (project in file("modules/config/circe-config-source"))
  .settings(commonSettings)
  .settings(
    name := "controller-circe-config-source",
    libraryDependencies ++= Seq(
      "io.chrisdavenport"      %% "log4cats-slf4j"          % log4catsVer,
      "org.scala-lang.modules" %% "scala-collection-compat" % collectionCompatVer
    )
  )
  .dependsOn(configSource, events, extruder, schedule, tracedConfigSource)

lazy val remote = (project in file("modules/remote/remote"))
  .settings(commonSettings)
  .settings(name := "controller-remote")
  .dependsOn(model, errors)

lazy val remoteStore = (project in file("modules/remote/remote-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-remote-store",
    libraryDependencies ++= Seq(
      "eu.timepit"       %% "refined"           % refinedVer,
      "io.janstenpickle" %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(model, configSource)

lazy val remoteConfig = (project in file("modules/remote/remote-config"))
  .settings(commonSettings)
  .settings(name := "controller-remote-config", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(model, configSource, circeConfigSource)

lazy val arrow = (project in file("modules/arrow"))
  .settings(commonSettings)
  .settings(name := "controller-arrow", libraryDependencies ++= Seq("org.typelevel" %% "cats-core" % catsVer))
  .dependsOn(model)

lazy val events = (project in file("modules/events/events"))
  .settings(commonSettings)
  .settings(
    name := "controller-events",
    libraryDependencies ++= Seq(
      "org.typelevel"     %% "cats-effect"       % catsEffectVer,
      "co.fs2"            %% "fs2-core"          % fs2Ver,
      "io.chrisdavenport" %% "log4cats-slf4j"    % log4catsVer,
      "io.janstenpickle"  %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(arrow, model, errors)

lazy val componentsEventsState = (project in file("modules/components/components-events-state"))
  .settings(commonSettings)
  .settings(
    name := "controller-components-events-state",
    libraryDependencies ++= Seq(
      "org.typelevel"     %% "cats-effect"    % catsEffectVer,
      "co.fs2"            %% "fs2-core"       % fs2Ver,
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer
    )
  )
  .dependsOn(model, activity, events, components)

lazy val eventDrivenConfigSource = (project in file("modules/events/event-driven-config-source"))
  .settings(commonSettings)
  .settings(
    name := "controller-event-driven-config-source",
    libraryDependencies ++= Seq(
      "io.chrisdavenport"      %% "log4cats-slf4j"          % log4catsVer,
      "org.scala-lang.modules" %% "scala-collection-compat" % collectionCompatVer
    )
  )
  .dependsOn(configSource, cache, tracedConfigSource, events, model)

lazy val tracedRemote = (project in file("modules/remote/trace-remote"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace-remote",
    libraryDependencies ++= Seq("io.janstenpickle" %% "trace4cats-inject" % trace4catsVer)
  )
  .dependsOn(remote)

lazy val broadlink = (project in file("modules/plugins/broadlink"))
  .settings(commonSettings)
  .settings(
    name := "controller-plugin-broadlink",
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
    switchStore,
    switch,
    eventsSwitch,
    tracedSwitch,
    remoteControl,
    pollingSwitch,
    dynamicDiscovery
  )

lazy val `broadlink-server` = (project in file("modules/plugins/broadlink-server"))
  .settings(commonSettings)
  .settings(graalSettings)
  .settings(
    name := "controller-plugin-broadlink-server",
    libraryDependencies ++= Seq(
      "org.http4s"       %% "http4s-ember-client"      % http4sVer,
      "ch.qos.logback"   % "logback-classic"           % "1.2.3",
      "io.janstenpickle" %% "trace4cats-inject"        % trace4catsVer,
      "io.janstenpickle" %% "trace4cats-avro-exporter" % trace4catsVer
    )
  )
  .dependsOn(
    pluginApi,
    broadlink,
    remoteStore,
    gitRemoteStore,
    remoteConfig,
    switchStore,
    switchConfig,
    advertiser,
    server,
    websocketClientEvents,
    trace,
    prometheusTrace,
    eventCommands,
    extruder,
    discoveryConfig
  )
  .enablePlugins(GraalVMNativeImagePlugin)

lazy val switch = (project in file("modules/switch/switch"))
  .settings(commonSettings)
  .settings(
    name := "controller-switch",
    libraryDependencies ++= Seq(
      "org.typelevel"    %% "cats-core"         % catsVer,
      "eu.timepit"       %% "refined"           % refinedVer,
      "org.typelevel"    %% "kittens"           % kittensVer,
      "io.janstenpickle" %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(model, errors)

lazy val switchStore = (project in file("modules/switch/switch-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-switch-store",
    libraryDependencies ++= Seq(
      "eu.timepit"       %% "refined"           % refinedVer,
      "io.janstenpickle" %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(model, configSource)

lazy val switchConfig = (project in file("modules/switch/switch-config"))
  .settings(commonSettings)
  .settings(name := "controller-switch-config", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(model, configSource, circeConfigSource)

lazy val pollingSwitch = (project in file("modules/switch/polling-switch"))
  .settings(commonSettings)
  .settings(name := "controller-polling-switch")
  .dependsOn(switch, poller)

lazy val tplink = (project in file("modules/plugins/tplink"))
  .settings(commonSettings)
  .settings(
    name := "controller-plugin-tplink",
    libraryDependencies ++= Seq(
      "io.circe"      %% "circe-core"   % circeVer,
      "io.circe"      %% "circe-parser" % circeVer,
      "eu.timepit"    %% "refined"      % refinedVer,
      "eu.timepit"    %% "refined"      % refinedVer,
      "org.typelevel" %% "cats-effect"  % catsEffectVer
    )
  )
  .dependsOn(
    components,
    dynamicDiscovery,
    pollingSwitch,
    schedule,
    eventsSwitch,
    tracedSwitch,
    tracedConfigSource,
    tracedRemote
  )

lazy val `tplink-server` = (project in file("modules/plugins/tplink-server"))
  .settings(commonSettings)
  .settings(graalSettings)
  .settings(
    name := "controller-plugin-tplink-server",
    libraryDependencies ++= Seq(
      "ch.qos.logback"   % "logback-classic"           % "1.2.3",
      "io.janstenpickle" %% "trace4cats-inject"        % trace4catsVer,
      "io.janstenpickle" %% "trace4cats-avro-exporter" % trace4catsVer
    )
  )
  .dependsOn(
    pluginApi,
    tplink,
    advertiser,
    server,
    websocketClientEvents,
    trace,
    prometheusTrace,
    eventCommands,
    extruder
  )
  .enablePlugins(GraalVMNativeImagePlugin)

lazy val virtualSwitch = (project in file("modules/switch/virtual-switch"))
  .settings(commonSettings)
  .settings(name := "controller-virtual-switch", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(switchStore, switchConfig, pollingSwitch, tracedSwitch, eventsSwitch, remoteControl)

lazy val multiSwitch = (project in file("modules/switch/multi-switch"))
  .settings(commonSettings)
  .settings(
    name := "controller-multi-switch",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVer,
      "eu.timepit"    %% "refined"     % refinedVer
    )
  )
  .dependsOn(switchConfig, switch, tracedSwitch, eventsSwitch)

lazy val deconzBridge = (project in file("modules/deconz-bridge"))
  .settings(commonSettings)
  .settings(
    name := "controller-deconz-bridge",
    libraryDependencies ++= Seq(
      "eu.timepit"        %% "refined"           % refinedVer,
      "eu.timepit"        %% "refined-cats"      % refinedVer,
      "org.typelevel"     %% "kittens"           % kittensVer,
      "io.chrisdavenport" %% "log4cats-slf4j"    % log4catsVer,
      "io.circe"          %% "circe-parser"      % circeVer,
      "io.circe"          %% "circe-generic"     % circeVer,
      "io.janstenpickle"  %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(arrow, events, model, circeConfigSource, websocketClient)

lazy val `deconz-server` = (project in file("modules/deconz-server"))
  .settings(commonSettings)
  .settings(graalSettings)
  .settings(
    name := "controller-deconz-server",
    libraryDependencies ++= Seq(
      "ch.qos.logback"   % "logback-classic"           % "1.2.3",
      "io.janstenpickle" %% "trace4cats-inject"        % trace4catsVer,
      "io.janstenpickle" %% "trace4cats-avro-exporter" % trace4catsVer
    )
  )
  .dependsOn(deconzBridge, server, websocketClientEvents, trace, prometheusTrace)
  .enablePlugins(GraalVMNativeImagePlugin)

lazy val tracedSwitch = (project in file("modules/switch/trace-switch"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace-switch",
    libraryDependencies ++= Seq("io.janstenpickle" %% "trace4cats-inject" % trace4catsVer)
  )
  .dependsOn(switch)

lazy val eventsSwitch = (project in file("modules/switch/events-switch"))
  .settings(commonSettings)
  .settings(name := "controller-events-switch")
  .dependsOn(events, switch)

lazy val cronScheduler = (project in file("modules/cron-scheduler"))
  .settings(commonSettings)
  .settings(
    name := "controller-cron-scheduler",
    libraryDependencies ++= Seq(
      "eu.timepit"        %% "fs2-cron-core"     % "0.2.2",
      "io.chrisdavenport" %% "log4cats-slf4j"    % log4catsVer,
      "io.chrisdavenport" %% "fuuid"             % "0.4.0",
      "io.janstenpickle"  %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(arrow, schedule, events, configSource, extruder, circeConfigSource)

lazy val schedule = (project in file("modules/schedule"))
  .settings(commonSettings)
  .settings(
    name := "controller-schedule",
    libraryDependencies ++= Seq(
      "eu.timepit"    %% "refined"      % refinedVer,
      "eu.timepit"    %% "refined-cats" % refinedVer,
      "org.typelevel" %% "kittens"      % kittensVer
    )
  )
  .dependsOn(model)

lazy val remoteControl = (project in file("modules/remote/remote-control"))
  .settings(commonSettings)
  .settings(
    name := "controller-remote-control",
    libraryDependencies ++= Seq(
      "eu.timepit"       %% "refined"           % refinedVer,
      "org.typelevel"    %% "cats-core"         % catsVer,
      "io.janstenpickle" %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(events, remote, remoteStore)

lazy val `macro` = (project in file("modules/macro/macro"))
  .settings(commonSettings)
  .settings(
    name := "controller-macro",
    libraryDependencies ++= Seq(
      "org.typelevel"    %% "cats-effect"       % catsEffectVer,
      "io.janstenpickle" %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(remoteControl, switch, macroStore, configSource, errors)

lazy val macroConfig = (project in file("modules/macro/macro-config"))
  .settings(commonSettings)
  .settings(name := "controller-macro-config", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(model, configSource, circeConfigSource)

lazy val macroStore = (project in file("modules/macro/macro-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-macro-store",
    libraryDependencies ++= Seq(
      "eu.timepit"       %% "refined"           % refinedVer,
      "io.janstenpickle" %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(model, configSource)

lazy val context = (project in file("modules/context"))
  .settings(commonSettings)
  .settings(name := "controller-context")
  .dependsOn(activity, `macro`)

lazy val eventCommands = (project in file("modules/events/event-commands"))
  .settings(commonSettings)
  .settings(
    name := "controller-event-commands",
    libraryDependencies ++= Seq("io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer)
  )
  .dependsOn(context, events, dynamicDiscovery)

lazy val activity = (project in file("modules/activity/activity"))
  .settings(commonSettings)
  .settings(
    name := "controller-activity",
    libraryDependencies ++= Seq("io.janstenpickle" %% "trace4cats-inject" % trace4catsVer)
  )
  .dependsOn(`macro`, tracedSwitch, eventsSwitch, activityStore)

lazy val activityConfig = (project in file("modules/activity/activity-config"))
  .settings(commonSettings)
  .settings(name := "controller-activity-config", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(model, configSource, circeConfigSource)

lazy val activityStore = (project in file("modules/activity/activity-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-activity-store",
    libraryDependencies ++= Seq(
      "eu.timepit"       %% "refined"           % refinedVer,
      "io.janstenpickle" %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(model, configSource)

lazy val poller = (project in file("modules/poller"))
  .settings(commonSettings)
  .settings(
    name := "controller-poller",
    libraryDependencies ++= Seq(
      "co.fs2"                 %% "fs2-core"                % fs2Ver,
      "io.chrisdavenport"      %% "log4cats-slf4j"          % log4catsVer,
      "eu.timepit"             %% "refined"                 % refinedVer,
      "io.janstenpickle"       %% "trace4cats-inject"       % trace4catsVer,
      "org.typelevel"          %% "cats-effect"             % catsEffectVer,
      "org.scala-lang.modules" %% "scala-collection-compat" % collectionCompatVer
    )
  )
  .dependsOn(arrow)

lazy val stats = (project in file("modules/stats/stats"))
  .settings(commonSettings)
  .settings(
    name := "controller-stats",
    libraryDependencies ++= Seq(
      "co.fs2"                 %% "fs2-core"                % fs2Ver,
      "eu.timepit"             %% "refined"                 % refinedVer,
      "io.chrisdavenport"      %% "log4cats-slf4j"          % log4catsVer,
      "org.scala-lang.modules" %% "scala-collection-compat" % collectionCompatVer
    )
  )
  .dependsOn(remoteControl, activity, `macro`, switch, configSource, schedule)

lazy val prometheusStats = (project in file("modules/stats/prometheus-stats"))
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

lazy val prometheusTrace = (project in file("modules/trace/prometheus-trace"))
  .settings(commonSettings)
  .settings(
    name := "controller-prometheus-trace",
    libraryDependencies ++= Seq(
      "io.chrisdavenport" %% "log4cats-slf4j"             % log4catsVer,
      "io.prometheus"     % "simpleclient"                % prometheusVer,
      "io.janstenpickle"  %% "trace4cats-exporter-common" % trace4catsVer
    )
  )

lazy val cache = (project in file("modules/cache"))
  .settings(commonSettings)
  .settings(
    name := "controller-cache",
    libraryDependencies ++= Seq(
      "com.github.cb372" %% "scalacache-caffeine"    % scalaCacheVer,
      "com.github.cb372" %% "scalacache-cats-effect" % scalaCacheVer,
      "io.prometheus"    % "simpleclient_hotspot"    % prometheusVer,
      "org.typelevel"    %% "cats-effect"            % catsEffectVer
    )
  )

lazy val gitRemoteStore = (project in file("modules/remote/git-remote-command-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-git-remote-command-store",
    libraryDependencies ++= Seq(
      "commons-io"        % "commons-io"         % "2.7",
      "io.chrisdavenport" %% "log4cats-slf4j"    % log4catsVer,
      "org.typelevel"     %% "cats-effect"       % catsEffectVer,
      "org.typelevel"     %% "kittens"           % kittensVer,
      "io.janstenpickle"  %% "trace4cats-inject" % trace4catsVer,
      "org.http4s"        %% "http4s-dsl"        % http4sVer,
      "org.http4s"        %% "http4s-client"     % http4sVer,
      "org.http4s"        %% "http4s-circe"      % http4sVer,
      "io.circe"          %% "circe-generic"     % circeVer,
      "eu.timepit"        %% "refined-cats"      % refinedVer
    )
  )
  .dependsOn(poller, remoteStore)

lazy val sonos = (project in file("modules/plugins/sonos"))
  .settings(commonSettings)
  .settings(
    name := "controller-plugin-sonos",
    libraryDependencies ++= Seq("io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer)
  )
  .dependsOn(
    components,
    sonosClientSubmodule,
    cache,
    remoteControl,
    tracedRemote,
    switch,
    eventsSwitch,
    tracedSwitch,
    configSource,
    tracedConfigSource,
    dynamicDiscovery
  )

lazy val `sonos-server` = (project in file("modules/plugins/sonos-server"))
  .settings(commonSettings)
  .settings(graalSettings)
  .settings(
    name := "controller-plugin-sonos-server",
    libraryDependencies ++= Seq(
      "ch.qos.logback"   % "logback-classic"           % "1.2.3",
      "io.janstenpickle" %% "trace4cats-inject"        % trace4catsVer,
      "io.janstenpickle" %% "trace4cats-avro-exporter" % trace4catsVer
    )
  )
  .dependsOn(
    pluginApi,
    sonos,
    advertiser,
    server,
    websocketClientEvents,
    trace,
    prometheusTrace,
    eventCommands,
    extruder
  )
  .enablePlugins(GraalVMNativeImagePlugin)

lazy val `kodi-server` = (project in file("modules/plugins/kodi-server"))
  .settings(commonSettings)
  .settings(graalSettings)
  .settings(
    name := "controller-plugin-kodi-server",
    libraryDependencies ++= Seq(
      "org.http4s"       %% "http4s-jdk-http-client"   % "0.3.0",
      "ch.qos.logback"   % "logback-classic"           % "1.2.3",
      "io.janstenpickle" %% "trace4cats-inject"        % trace4catsVer,
      "io.janstenpickle" %% "trace4cats-avro-exporter" % trace4catsVer
    )
  )
  .dependsOn(
    pluginApi,
    kodi,
    advertiser,
    server,
    websocketClientEvents,
    trace,
    prometheusTrace,
    eventCommands,
    extruder,
    discoveryConfig
  )
  .enablePlugins(GraalVMNativeImagePlugin)

lazy val kodi = (project in file("modules/plugins/kodi"))
  .settings(commonSettings)
  .settings(
    name := "controller-plugin-kodi",
    libraryDependencies ++= Seq(
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer,
      "org.http4s"        %% "http4s-dsl"     % http4sVer,
      "org.http4s"        %% "http4s-client"  % http4sVer,
      "org.http4s"        %% "http4s-circe"   % http4sVer,
      "io.circe"          %% "circe-generic"  % circeVer,
      "org.jmdns"         % "jmdns"           % jmdnsVer
    )
  )
  .dependsOn(
    components,
    remoteControl,
    cache,
    eventsSwitch,
    tracedRemote,
    switch,
    pollingSwitch,
    tracedSwitch,
    configSource,
    tracedConfigSource,
    dynamicDiscovery
  )

lazy val dynamicDiscovery = (project in file("modules/discovery/dynamic-discovery"))
  .settings(commonSettings)
  .settings(
    name := "controller-dynamic-discovery",
    libraryDependencies ++= Seq("org.typelevel" %% "kittens" % kittensVer)
  )
  .dependsOn(events, poller)

lazy val discoveryConfig = (project in file("modules/discovery/discovery-config"))
  .settings(commonSettings)
  .settings(name := "controller-discovery-config", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(model, configSource, circeConfigSource)

lazy val trace = (project in file("modules/trace/trace"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace",
    libraryDependencies ++= Seq("io.janstenpickle" %% "trace4cats-inject" % trace4catsVer)
  )

lazy val homekit = (project in file("modules/homekit"))
  .settings(commonSettings)
  .settings(
    name := "controller-homekit",
    libraryDependencies ++= Seq(
      "co.fs2"                 %% "fs2-core"             % fs2Ver,
      "eu.timepit"             %% "refined"              % refinedVer,
      "io.chrisdavenport"      %% "log4cats-slf4j"       % log4catsVer,
      "io.extruder"            %% "extruder-cats-effect" % extruderVer,
      "io.extruder"            %% "extruder-typesafe"    % extruderVer,
      "org.apache.commons"     % "commons-text"          % "1.8",
      "org.typelevel"          %% "cats-effect"          % catsEffectVer,
      "io.janstenpickle"       %% "trace4cats-inject"    % trace4catsVer,
      "org.scala-lang.modules" %% "scala-java8-compat"   % "0.9.1"
    )
  )
  .dependsOn(events, extruder, poller, switch, hapJavaSubmodule)

lazy val homekitServer = (project in file("modules/homekit-server"))
  .settings(commonSettings)
  .settings(
    name := "controller-homekit-server",
    packageName in Docker := "controller-homekit-server",
    dockerRepository := Some("janstenpickle"),
    dockerUpdateLatest := true,
    dockerBaseImage := "openjdk:13",
    daemonUserUid in Docker := Some("9000"),
    javaOptions in Universal ++= Seq("-Djava.net.preferIPv4Stack=true"),
    libraryDependencies ++= Seq(
      "ch.qos.logback"   % "logback-classic"           % "1.2.3",
      "io.janstenpickle" %% "trace4cats-inject"        % trace4catsVer,
      "io.janstenpickle" %% "trace4cats-avro-exporter" % trace4catsVer
    )
  )
  .dependsOn(homekit, server, websocketClientEvents, trace, prometheusTrace)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val mqttClient = (project in file("modules/events/mqtt-client"))
  .settings(commonSettings)
  .settings(
    name := "controller-mqtt-client",
    libraryDependencies ++= Seq(
      "org.typelevel"          %% "cats-effect"          % catsEffectVer,
      "co.fs2"                 %% "fs2-reactive-streams" % fs2Ver,
      "org.scala-lang.modules" %% "scala-java8-compat"   % "0.9.0",
      "com.hivemq"             % "hivemq-mqtt-client"    % "1.2.0"
    )
  )

lazy val mqttEvents = (project in file("modules/events/mqtt-events"))
  .settings(commonSettings)
  .settings(
    name := "controller-mqtt-events",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"          % catsEffectVer,
      "io.circe"      %% "circe-parser"         % circeVer,
      "io.circe"      %% "circe-generic-extras" % circeVer,
      "io.circe"      %% "circe-refined"        % circeVer
    )
  )
  .dependsOn(events, mqttClient, model)

lazy val websocketEvents = (project in file("modules/events/websocket-events"))
  .settings(commonSettings)
  .settings(
    name := "controller-websocket-events",
    libraryDependencies ++= Seq(
      "org.http4s"       %% "http4s-circe"      % http4sVer,
      "org.http4s"       %% "http4s-core"       % http4sVer,
      "org.http4s"       %% "http4s-dsl"        % http4sVer,
      "org.http4s"       %% "http4s-server"     % http4sVer,
      "io.janstenpickle" %% "trace4cats-inject" % trace4catsVer
    )
  )
  .dependsOn(arrow, events, model, componentsEventsState)

lazy val websocketClientEvents = (project in file("modules/events/websocket-client-events"))
  .settings(commonSettings)
  .settings(
    name := "controller-websocket-client-events",
    libraryDependencies ++= Seq(
      "io.janstenpickle"   %% "trace4cats-inject" % trace4catsVer,
      "org.java-websocket" % "Java-WebSocket"     % "1.5.1"
    )
  )
  .dependsOn(arrow, events, model, componentsEventsState, websocketClient)

lazy val eventDrivenSwitches = (project in file("modules/switch/event-driven-switches"))
  .settings(commonSettings)
  .settings(
    name := "controller-event-driven-switches",
    libraryDependencies ++= Seq("io.chrisdavenport" %% "mapref" % maprefVer)
  )
  .dependsOn(events, cache, model, switch, tracedSwitch)

lazy val eventDrivenRemoteControls = (project in file("modules/remote/event-driven-remote-controls"))
  .settings(commonSettings)
  .settings(name := "controller-event-driven-remote-controls")
  .dependsOn(events, cache, model, remoteControl, eventDrivenConfigSource)

lazy val eventDrivenActivity = (project in file("modules/activity/event-driven-activity"))
  .settings(commonSettings)
  .settings(
    name := "controller-event-driven-activity",
    libraryDependencies ++= Seq("io.chrisdavenport" %% "mapref" % maprefVer)
  )
  .dependsOn(events, cache, model, activity, eventDrivenConfigSource)

lazy val eventDrivenDeviceRename = (project in file("modules/discovery/event-driven-device-rename"))
  .settings(commonSettings)
  .settings(
    name := "controller-event-driven-device-rename",
    libraryDependencies ++= Seq("io.chrisdavenport" %% "mapref" % maprefVer)
  )
  .dependsOn(events, cache, model, dynamicDiscovery)

lazy val kafkaEvents = (project in file("modules/events/kafka-events"))
  .settings(commonSettings)
  .settings(
    name := "controller-kafka-events",
    libraryDependencies ++= Seq(
      "co.fs2"          %% "fs2-core"             % fs2Ver,
      "com.github.fd4s" %% "fs2-kafka"            % "1.0.0",
      "org.typelevel"   %% "cats-effect"          % catsEffectVer,
      "io.circe"        %% "circe-parser"         % circeVer,
      "io.circe"        %% "circe-generic-extras" % circeVer,
      "io.circe"        %% "circe-refined"        % circeVer
    )
  )
  .dependsOn(events, model)

lazy val websocketClient = (project in file("modules/websocket-client"))
  .settings(commonSettings)
  .settings(
    name := "controller-websocket-client",
    libraryDependencies ++= Seq(
      "org.typelevel"      %% "cats-effect"   % catsEffectVer,
      "co.fs2"             %% "fs2-core"      % fs2Ver,
      "org.java-websocket" % "Java-WebSocket" % "1.5.1"
    )
  )
  .dependsOn(arrow)

lazy val sonosClientSubmodule = (project in file("submodules/sonos-controller"))
  .settings(commonSettings)
  .settings(
    organization := "com.vmichalak",
    name := "sonos-controller",
    libraryDependencies ++= Seq(
      "com.squareup.okhttp3" % "okhttp"                         % "4.7.2",
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
      "javax.xml.bind" % "jaxb-api"     % "2.3.1",
      "org.slf4j"      % "slf4j-api"    % "1.7.22",
      "junit"          % "junit"        % "4.12" % Test,
      "org.slf4j"      % "slf4j-simple" % "1.7.22" % Test
    )
  )

lazy val hapJavaSubmodule = (project in file("submodules/HAP-Java"))
  .settings(commonSettings)
  .settings(
    organization := "io.github.hap-java",
    name := "hap-java",
    libraryDependencies ++= Seq(
      "org.slf4j"        % "slf4j-api"       % "1.7.22",
      "io.netty"         % "netty-all"       % "4.1.48.Final",
      "com.nimbusds"     % "srp6a"           % "1.5.2",
      "org.bouncycastle" % "bcprov-jdk15on"  % "1.51",
      "net.vrallev.ecc"  % "ecc-25519-java"  % "1.0.1",
      "org.zeromq"       % "curve25519-java" % "0.1.0",
      "javax.json"       % "javax.json-api"  % "1.0",
      "org.glassfish"    % "javax.json"      % "1.0.4",
      "org.jmdns"        % "jmdns"           % jmdnsVer,
      "commons-io"       % "commons-io"      % "2.6",
      "junit"            % "junit"           % "4.12" % Test,
      "org.mockito"      % "mockito-all"     % "1.10.19" % Test
    )
  )
