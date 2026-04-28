import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._

val scala3Version = "3.3.4"

lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  organization := "net.andimiller.mcp",
  version := "0.9.0",
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-language:higherKinds",
    "-Wconf:msg=New anonymous class definition will be duplicated:s"  // Silence inline derivation warning
  )
)

// scoverage instrumentation produces JVM-only bytecode, so coverage must be disabled on every
// non-JVM platform — otherwise dependent Scala.js builds (notably the explorer) fail to link.
lazy val noCoverage: Seq[Setting[?]] = Seq(coverageEnabled := false)

lazy val publishSettings = Seq(
  useGpg               := true,
  pomIncludeRepository := { _ => false },
  publishMavenStyle    := true,
  publishTo            := {
    val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
    if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
    else localStaging.value
  },
  licenses             := Seq("MIT" -> url("https://opensource.org/licenses/MIT")),
  scmInfo              := Some(
    ScmInfo(url("https://github.com/andimiller/scala-mcp"), "scm:git@github.com:andimiller/scala-mcp.git")
  ),
  homepage             := Some(url("https://github.com/andimiller/scala-mcp")),
  developers           := List(
    Developer(
      id = "andimiller",
      name = "Andi Miller",
      email = "andi@andimiller.net",
      url = url("http://andimiller.net")
    )
  )
)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "mcp-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % "3.7.0",
      "co.fs2" %%% "fs2-core" % "3.13.0",
      "io.circe" %%% "circe-core" % "0.14.14",
      "io.circe" %%% "circe-generic" % "0.14.14",
      "io.circe" %%% "circe-parser" % "0.14.14",
      "com.softwaremill.sttp.apispec" %%% "apispec-model" % "0.11.10",
      "com.softwaremill.sttp.apispec" %%% "jsonschema-circe" % "0.11.10",
      "com.lihaoyi" %%% "sourcecode" % "0.4.2",
      "org.scalameta" %%% "munit" % "1.0.0" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test
    )
  )
  .jvmSettings(
    // JVM-specific settings
  )
  .jsSettings(noCoverage*)
  .nativeSettings(noCoverage*)

lazy val stdio = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/stdio"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "mcp-stdio",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % "1.0.0" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test
    )
  )
  .jsSettings(noCoverage*)
  .nativeSettings(noCoverage*)
  .dependsOn(core)

lazy val http4s = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/http4s"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "mcp-http4s",
    libraryDependencies ++= Seq(
      "org.http4s" %%% "http4s-dsl" % "0.23.33",
      "org.http4s" %%% "http4s-ember-server" % "0.23.33",
      "org.http4s" %%% "http4s-circe" % "0.23.33",
      "org.scalameta" %%% "munit" % "1.0.0" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test
    ),
    Compile / resourceGenerators += Def.task {
      val _ = (LocalRootProject / buildExplorer).value

      val distDir = (explorer / baseDirectory).value / "dist"
      val targetDir = (Compile / resourceManaged).value / "explorer"
      IO.delete(targetDir)
      IO.copyDirectory(distDir, targetDir)

      Path.allSubpaths(targetDir).map(_._1).toSeq
    }.taskValue
  )
  .jsSettings(noCoverage*)
  .dependsOn(core)


lazy val exampleDice = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/example-dice-mcp"))
  .settings(commonSettings)
  .settings(
    name := "example-dice-mcp",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-parse" % "1.1.0",
      "org.typelevel" %%% "cats-effect-std" % "3.7.0",
      "org.scalameta" %%% "munit" % "1.0.0" % Test
    )
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )
  .jsSettings(noCoverage*)
  .nativeSettings(noCoverage*)
  .dependsOn(core, stdio, goldenMunit % Test)

lazy val examplePomodoro = project
  .in(file("modules/example-pomodoro-mcp"))
  .settings(commonSettings)
  .settings(
    name := "example-pomodoro-mcp",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.5.6"
    )
  )
  .dependsOn(core.jvm, http4s.jvm, redis, goldenMunit.jvm % Test)

lazy val exampleNotebook = project
  .in(file("modules/example-shared-notebook-mcp"))
  .settings(commonSettings)
  .settings(
    name := "example-shared-notebook-mcp",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.5.6"
    )
  )
  .dependsOn(core.jvm, http4s.jvm)

lazy val exampleChat = project
  .in(file("modules/example-chat-mcp"))
  .settings(commonSettings)
  .settings(
    name := "example-chat-mcp",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.5.6"
    )
  )
  .dependsOn(core.jvm, http4s.jvm, redis)

lazy val exampleRpgCharacterCreator = project
  .in(file("modules/example-rpg-character-creator"))
  .settings(commonSettings)
  .settings(
    name := "example-rpg-character-creator",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic"  % "1.5.6",
      "net.andimiller" %% "enumerive-circe" % "1.0.0"
    )
  )
  .dependsOn(core.jvm, http4s.jvm, goldenMunit.jvm % Test)

lazy val exampleDns = project
  .in(file("modules/example-dns-mcp"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    name := "example-dns-mcp",
    publish / skip := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    scalaJSUseMainModuleInitializer := true
  )
  .dependsOn(core.js, http4s.js)

lazy val explorer = project
  .in(file("modules/explorer"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    scalaVersion := "3.6.4",
    name := "mcp-explorer",
    publish / skip := true,
    // scoverage instrumentation produces JVM-only bytecode that fails to link under Scala.js.
    // The explorer is a Scala.js UI; we don't measure its coverage anyway.
    coverageEnabled := false,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("net.andimiller.mcp.explorer.Main"),
    libraryDependencies ++= Seq(
      "io.indigoengine" %%% "tyrian-io" % "0.14.0",
      "org.http4s" %%% "http4s-dom" % "0.2.1",
      "org.http4s" %%% "http4s-circe" % "0.23.33"
    )
  )
  .dependsOn(core.js)

lazy val buildExplorer = taskKey[Unit]("Build explorer for production")

buildExplorer := {
  val log = streams.value.log

  // 1. Compile Scala.js (fast link — Parcel handles minification)
  log.info("Compiling explorer Scala.js...")
  (explorer / Compile / fastLinkJS).value

  // 2. Run yarn build in explorer directory
  log.info("Bundling with Parcel...")
  val explorerDir = (explorer / baseDirectory).value
  val exitCode = scala.sys.process.Process("yarn" :: "build" :: Nil, explorerDir).!
  if (exitCode != 0) sys.error("yarn build failed")

  log.info("Explorer build complete.")
}

lazy val goldenMunit = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/golden-munit"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "mcp-golden-munit",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit"            % "1.0.0",
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0",
      "co.fs2"        %%% "fs2-io"           % "3.13.0"
    )
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )
  .jsSettings(noCoverage*)
  .nativeSettings(noCoverage*)
  .dependsOn(core)

lazy val openapi = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/openapi"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "mcp-openapi",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.apispec" %%% "openapi-model" % "0.11.10",
      "com.softwaremill.sttp.apispec" %%% "openapi-circe" % "0.11.10",
      "org.scalameta"                  %%% "munit"             % "1.0.0"  % Test,
      "org.typelevel"                  %%% "munit-cats-effect" % "2.2.0"  % Test
    )
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "io.circe"                      %% "circe-yaml"           % "1.15.0"  % Test,
      "com.softwaremill.sttp.tapir"   %% "tapir-core"           % "1.11.40" % Test,
      "com.softwaremill.sttp.tapir"   %% "tapir-openapi-docs"  % "1.11.40" % Test
    )
  )
  .jsSettings(noCoverage*)
  .nativeSettings(noCoverage*)
  .dependsOn(core)

lazy val redis = project
  .in(file("modules/redis"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "mcp-redis",
    libraryDependencies ++= Seq(
      "dev.profunktor" %% "redis4cats-effects" % "2.0.3",
      "dev.profunktor" %% "redis4cats-streams"  % "2.0.3"
    )
  )
  .dependsOn(core.jvm, http4s.jvm)

lazy val tapir = project
  .in(file("modules/tapir"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "mcp-tapir",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core"         % "1.11.40",
      "com.softwaremill.sttp.tapir" %% "tapir-apispec-docs" % "1.11.40",
      "org.scalameta"               %% "munit"              % "1.0.0" % Test,
      "org.typelevel"               %% "munit-cats-effect"  % "2.2.0" % Test
    )
  )
  .dependsOn(core.jvm)

lazy val openapiMcpProxy = project
  .in(file("modules/openapi-mcp-proxy"))
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "net.andimiller.mcp.openapi",
    name := "openapi-mcp-proxy",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "io.circe"                      %% "circe-yaml"         % "1.15.0",
      "org.http4s"                    %% "http4s-ember-client" % "0.23.33",
      "org.http4s"                    %% "http4s-circe"       % "0.23.33",
      "com.monovore"                  %% "decline-effect"     % "2.6.1",
      "ch.qos.logback"                %  "logback-classic"    % "1.5.6",
      "org.scalameta"                 %% "munit"              % "1.0.0"  % Test,
      "org.typelevel"                 %% "munit-cats-effect"  % "2.2.0"  % Test
    ),
    assembly / mainClass := Some("net.andimiller.mcp.openapi.OpenApiMcpServer"),
    assembly / assemblyJarName := "openapi-mcp-proxy",
    assembly / assemblyPrependShellScript := Some(sbtassembly.AssemblyPlugin.defaultShellScript),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties")     => MergeStrategy.first
      case x if x.endsWith("module-info.class")                     => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
  .dependsOn(core.jvm, stdio.jvm, openapi.jvm)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    name := "scala-mcp",
    publish / skip := true
  )
  .aggregate(
    core.jvm, core.js, core.native,
    stdio.jvm, stdio.js, stdio.native,
    exampleDice.jvm, exampleDice.js, exampleDice.native,
    http4s.jvm, http4s.js,
    redis,
    tapir,
    examplePomodoro, exampleChat, exampleDns, exampleNotebook, exampleRpgCharacterCreator,
    explorer,
    goldenMunit.jvm, goldenMunit.js, goldenMunit.native,
    openapi.jvm, openapi.js, openapi.native,
    openapiMcpProxy
  )
