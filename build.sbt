import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._

val scala3Version = "3.3.4"

lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  organization := "dev.mcp",
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

lazy val http4s = project
  .in(file("modules/http4s"))
  .settings(commonSettings)
  .settings(
    name := "mcp-http4s",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % "0.23.28",
      "org.http4s" %% "http4s-ember-server" % "0.23.28",
      "org.http4s" %% "http4s-circe" % "0.23.28"
    )
  )
  .dependsOn(core.jvm)

lazy val tapir = project
  .in(file("modules/tapir"))
  .settings(commonSettings)
  .settings(
    name := "mcp-tapir",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core" % "1.11.11",
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % "1.11.11",
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % "1.11.11"
    )
  )
  .dependsOn(core.jvm)

lazy val examples = project
  .in(file("modules/examples"))
  .settings(commonSettings)
  .settings(
    name := "mcp-examples"
  )
  .dependsOn(core.jvm, stdio.jvm, http4s)

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
    http4s, tapir, examples
  )
