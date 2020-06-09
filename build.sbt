import com.typesafe.sbt.packager.docker.DockerPermissionStrategy

val catsVer = "2.1.1"
val catsEffectVer = "2.1.3"
val circeVer = "0.13.0"
val collectionCompatVer = "2.1.6"
val extruderVer = "0.11.0"
val fs2Ver = "2.3.0"
val http4sVer = "0.21.4"
val kittensVer = "2.1.0"
val jmdnsVer = "3.5.5"
val log4catsVer = "1.1.1"
val maprefVer = "0.1.1"
val natchezVer = "0.0.11"
val prometheusVer = "0.9.0"
val refinedVer = "0.9.14"
val scalaCacheVer = "0.28.0"
val scalaCheckVer = "1.13.5"
val scalaCheckShapelessVer = "1.1.8"
val scalaTestVer = "3.1.2"

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
  publishArtifact in packageDoc := false
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(name := "controller")
  .aggregate(
    advertiser,
    api,
    model,
    errors,
    components,
    eventCommands,
    remote,
    broadlink,
    remoteControl,
    circeConfigSource,
    `macro`,
    context,
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
    schedule,
    cronScheduler,
    prometheusStats,
    gitRemoteStore,
    homekit,
    mqttClient,
    mqttEvents,
    websocketEvents,
    deconzBridge,
    hapJavaSubmodule,
    eventDrivenSwitches,
    eventDrivenRemoteControls,
    eventDrivenActivity,
    eventDrivenDeviceRename
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
      "org.typelevel"     %% "cats-mtl-core"             % "0.7.1",
      ("org.tpolecat" %% "natchez-jaeger" % natchezVer).exclude("org.slf4j", "slf4j-jdk14")
    ),
    graalVMNativeImageOptions ++= Seq(
      "--verbose",
      "--no-server",
      "--no-fallback",
      "--static",
      "--enable-http",
      "--enable-https",
      "--enable-all-security-services",
      "--report-unsupported-elements-at-runtime",
      "--allow-incomplete-classpath",
      "-H:+ReportExceptionStackTraces",
      "-H:+ReportUnsupportedElementsAtRuntime",
      "-H:+TraceClassInitialization",
      "-H:+PrintClassInitialization",
      "-H:+RemoveSaturatedTypeFlows",
      "-H:+StackTrace",
      "-H:+JNI",
      "-H:-SpawnIsolates",
      "-H:-UseServiceLoaderFeature",
      "-H:ReflectionConfigurationFiles=../../../../reflection.json",
      "--install-exit-handlers",
      "--initialize-at-build-time=scala.runtime.Statics$VM",
      "--initialize-at-build-time=ch.qos.logback.classic.Logger",
      "--initialize-at-build-time=ch.qos.logback.core.status.StatusBase",
      "--initialize-at-build-time=ch.qos.logback.classic.Level",
      "--initialize-at-build-time=ch.qos.logback.core.spi.AppenderAttachableImpl",
      "--initialize-at-build-time=ch.qos.logback.core.status.InfoStatus",
      "--initialize-at-build-time=ch.qos.logback.core.status.ErrorStatus",
      "--initialize-at-build-time=org.slf4j.impl.StaticLoggerBinder",
      "--initialize-at-build-time=ch.qos.logback.core.CoreConstants",
      "--initialize-at-build-time=org.slf4j.LoggerFactory",
      "--initialize-at-run-time=io.netty.util.internal.logging.Log4JLogger",
      "--initialize-at-build-time=scala.Symbol$"
    ),
    mappings in Universal := {
      val universalMappings = (mappings in Universal).value

      universalMappings.filter {
        case (_, name) => !name.contains("slf4j-jdk14")
      }
    }
  )
  .dependsOn(
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
    homekit,
    kafkaEvents,
    mqttClient,
    mqttEvents,
    cronScheduler,
    deconzBridge,
    eventDrivenSwitches,
    websocketEvents,
    discoveryConfig
  )
  .enablePlugins(UniversalPlugin, JavaAppPackaging, DockerPlugin, PackPlugin, GraalVMNativeImagePlugin)

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

lazy val components = (project in file("modules/components"))
  .settings(commonSettings)
  .settings(name := "controller-components")
  .dependsOn(model, configSource, remoteControl, schedule, switch, dynamicDiscovery)

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
    libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer, "org.tpolecat" %% "natchez-core" % natchezVer)
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
      "org.typelevel"     %% "cats-effect"    % catsEffectVer,
      "co.fs2"            %% "fs2-core"       % fs2Ver,
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer
    )
  )
  .dependsOn(model, errors)

lazy val tracedRemote = (project in file("modules/remote/trace-remote"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace-remote",
    libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer)
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

lazy val switch = (project in file("modules/switch/switch"))
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
  .dependsOn(model, errors)

lazy val switchStore = (project in file("modules/switch/switch-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-switch-store",
    libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer, "org.tpolecat" %% "natchez-core" % natchezVer)
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
      "eu.timepit"                   %% "refined"                       % refinedVer,
      "eu.timepit"                   %% "refined-cats"                  % refinedVer,
      "org.typelevel"                %% "kittens"                       % kittensVer,
      "io.chrisdavenport"            %% "log4cats-slf4j"                % log4catsVer,
      "com.softwaremill.sttp.client" %% "async-http-client-backend-fs2" % "2.0.0",
      "io.circe"                     %% "circe-parser"                  % circeVer,
      "io.circe"                     %% "circe-generic"                 % circeVer,
      "org.tpolecat"                 %% "natchez-core"                  % natchezVer
    )
  )
  .dependsOn(arrow, events, model, circeConfigSource)

lazy val tracedSwitch = (project in file("modules/switch/trace-switch"))
  .settings(commonSettings)
  .settings(
    name := "controller-trace-switch",
    libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer)
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
      "eu.timepit"        %% "fs2-cron-core"  % "0.2.2",
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer,
      "io.chrisdavenport" %% "fuuid"          % "0.4.0",
      "org.tpolecat"      %% "natchez-core"   % natchezVer
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
      "eu.timepit"    %% "refined"      % refinedVer,
      "org.typelevel" %% "cats-core"    % catsVer,
      "org.tpolecat"  %% "natchez-core" % natchezVer
    )
  )
  .dependsOn(events, remote, remoteStore)

lazy val `macro` = (project in file("modules/macro/macro"))
  .settings(commonSettings)
  .settings(
    name := "controller-macro",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"  % catsEffectVer,
      "org.tpolecat"  %% "natchez-core" % natchezVer
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
    libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer, "org.tpolecat" %% "natchez-core" % natchezVer)
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
  .settings(name := "controller-activity", libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer))
  .dependsOn(`macro`, tracedSwitch, eventsSwitch, activityStore)

lazy val activityConfig = (project in file("modules/activity/activity-config"))
  .settings(commonSettings)
  .settings(name := "controller-activity-config", libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer))
  .dependsOn(model, configSource, circeConfigSource)

lazy val activityStore = (project in file("modules/activity/activity-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-activity-store",
    libraryDependencies ++= Seq("eu.timepit" %% "refined" % refinedVer, "org.tpolecat" %% "natchez-core" % natchezVer)
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
      "org.tpolecat"           %% "natchez-core"            % natchezVer,
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
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVer,
      "io.prometheus"     % "simpleclient"    % prometheusVer,
      "org.tpolecat"      %% "natchez-core"   % natchezVer
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

lazy val gitRemoteStore = (project in file("modules/remote/git-remote-command-store"))
  .settings(commonSettings)
  .settings(
    name := "controller-git-remote-command-store",
    libraryDependencies ++= Seq(
      "commons-io"        % "commons-io"      % "2.7",
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
  .settings(name := "controller-trace", libraryDependencies ++= Seq("org.tpolecat" %% "natchez-core" % natchezVer))

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
      "org.tpolecat"           %% "natchez-core"         % natchezVer,
      "org.scala-lang.modules" %% "scala-java8-compat"   % "0.9.1"
    )
  )
  .dependsOn(events, extruder, poller, switch, hapJavaSubmodule)

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
      "org.http4s"   %% "http4s-circe"  % http4sVer,
      "org.http4s"   %% "http4s-core"   % http4sVer,
      "org.http4s"   %% "http4s-dsl"    % http4sVer,
      "org.http4s"   %% "http4s-server" % http4sVer,
      "org.tpolecat" %% "natchez-core"  % natchezVer
    )
  )
  .dependsOn(arrow, events, model)

lazy val eventDrivenSwitches = (project in file("modules/switch/event-driven-switches"))
  .settings(commonSettings)
  .settings(
    name := "controller-event-driven-switches",
    libraryDependencies ++= Seq("io.chrisdavenport" %% "mapref" % maprefVer)
  )
  .dependsOn(events, model, switch, tracedSwitch)

lazy val eventDrivenRemoteControls = (project in file("modules/remote/event-driven-remote-controls"))
  .settings(commonSettings)
  .settings(name := "controller-event-driven-remote-controls")
  .dependsOn(events, model, remoteControl)

lazy val eventDrivenActivity = (project in file("modules/activity/event-driven-activity"))
  .settings(commonSettings)
  .settings(
    name := "controller-event-driven-activity",
    libraryDependencies ++= Seq("io.chrisdavenport" %% "mapref" % maprefVer)
  )
  .dependsOn(events, model, activity)

lazy val eventDrivenDeviceRename = (project in file("modules/discovery/event-driven-device-rename"))
  .settings(commonSettings)
  .settings(
    name := "controller-event-driven-device-rename",
    libraryDependencies ++= Seq("io.chrisdavenport" %% "mapref" % maprefVer)
  )
  .dependsOn(events, model, dynamicDiscovery)

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
      "io.netty"         % "netty-all"       % "4.1.43.Final",
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
