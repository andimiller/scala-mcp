import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._

val scala3Version = "3.3.4"

lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  organization := "net.andimiller.mcp",
  version := "0.1.0-SNAPSHOT",
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-language:higherKinds",
    "-Wconf:msg=New anonymous class definition will be duplicated:s"  // Silence inline derivation warning
  )
)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "mcp-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % "3.7.0",
      "co.fs2" %%% "fs2-core" % "3.13.0",
      "io.circe" %%% "circe-core" % "0.14.10",
      "io.circe" %%% "circe-generic" % "0.14.10",
      "io.circe" %%% "circe-parser" % "0.14.10",
      "com.lihaoyi" %%% "sourcecode" % "0.4.2",
      "org.scalameta" %%% "munit" % "1.0.0" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test
    )
  )
  .jvmSettings(
    // JVM-specific settings
  )
  .jsSettings(
    // Scala.js-specific settings
  )
  .nativeSettings(
    // Scala Native-specific settings
  )

lazy val stdio = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/stdio"))
  .settings(commonSettings)
  .settings(
    name := "mcp-stdio"
  )
  .dependsOn(core)

lazy val http4s = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/http4s"))
  .settings(commonSettings)
  .settings(
    name := "mcp-http4s",
    libraryDependencies ++= Seq(
      "org.http4s" %%% "http4s-dsl" % "0.23.33",
      "org.http4s" %%% "http4s-ember-server" % "0.23.33",
      "org.http4s" %%% "http4s-circe" % "0.23.33"
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
  .dependsOn(core)


lazy val exampleDice = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/example-dice-mcp"))
  .settings(commonSettings)
  .settings(
    name := "example-dice-mcp",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-parse" % "1.1.0",
      "org.typelevel" %%% "cats-effect-std" % "3.7.0",
      "org.scalameta" %%% "munit" % "1.0.0" % Test
    )
  )
  .dependsOn(core, stdio)

lazy val examplePomodoro = project
  .in(file("modules/example-pomodoro-mcp"))
  .settings(commonSettings)
  .settings(
    name := "example-pomodoro-mcp",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.5.6"
    )
  )
  .dependsOn(core.jvm, http4s.jvm)

lazy val exampleDns = project
  .in(file("modules/example-dns-mcp"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    name := "example-dns-mcp",
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

lazy val openapiMcpProxy = project
  .in(file("modules/openapi-mcp-proxy"))
  .settings(commonSettings)
  .settings(
    name := "openapi-mcp-proxy",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.apispec" %% "openapi-model"      % "0.11.10",
      "com.softwaremill.sttp.apispec" %% "openapi-circe"      % "0.11.10",
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
  .dependsOn(core.jvm, stdio.jvm)

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
    examplePomodoro, exampleDns,
    explorer,
    openapiMcpProxy
  )
