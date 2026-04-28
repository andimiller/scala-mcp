package net.andimiller.mcp.openapi

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import net.andimiller.mcp.core.server.ServerBuilder
import net.andimiller.mcp.stdio.StdioTransport
import org.http4s.ember.client.EmberClientBuilder

object OpenApiMcpServer
    extends CommandIOApp(
      name = "openapi-mcp-proxy",
      header = "Expose OpenAPI operations as MCP tools",
      version = BuildInfo.version
    ):

  private val console = Console[IO]

  private val specArg = Opts.argument[String](metavar = "spec")

  private val proxyCmd = Opts.subcommand("proxy", "Run the MCP stdio proxy for selected operations") {
    (specArg, Opts.arguments[String](metavar = "operationId")).mapN { (spec, ops) =>
      runProxy(spec, ops.toList)
    }
  }

  private val listCmd = Opts.subcommand("list", "List all operationIds in the spec") {
    specArg.map { spec =>
      runList(spec)
    }
  }

  // --- mcp subcommand group ---

  private val agentOpt: Opts[ConfigFormat] =
    Opts
      .option[String]("agent", "Target agent: claude, claude-desktop, cursor, opencode")
      .withDefault("claude")
      .mapValidated { s =>
        s.toLowerCase match
          case "claude"         => cats.data.Validated.valid(ConfigFormat.Claude)
          case "claude-desktop" => cats.data.Validated.valid(ConfigFormat.ClaudeDesktop)
          case "cursor"         => cats.data.Validated.valid(ConfigFormat.Cursor)
          case "opencode"       => cats.data.Validated.valid(ConfigFormat.OpenCode)
          case other            =>
            cats.data.Validated.invalidNel(s"Unknown agent '$other'. Choose: claude, claude-desktop, cursor, opencode")
      }

  private val mcpAddCmd: Opts[ConfigFormat => IO[ExitCode]] =
    Opts.subcommand("add", "Validate operations and register in config") {
      (specArg, Opts.arguments[String](metavar = "operationId").orNone).mapN { (spec, ops) => (fmt: ConfigFormat) =>
        runMcpAdd(fmt, spec, ops.map(_.toList).getOrElse(Nil))
      }
    }

  private val mcpDelCmd: Opts[ConfigFormat => IO[ExitCode]] =
    Opts.subcommand("del", "Remove an entry from config") {
      Opts.argument[String](metavar = "name").map(name => (fmt: ConfigFormat) => runMcpDel(fmt, name))
    }

  private val mcpManageCmd: Opts[ConfigFormat => IO[ExitCode]] =
    Opts.subcommand("manage", "Interactive shell for managing config") {
      Opts((fmt: ConfigFormat) => runMcpManage(fmt))
    }

  private val mcpCmd = Opts.subcommand("mcp", "Manage MCP server entries") {
    (agentOpt, mcpAddCmd.orElse(mcpDelCmd).orElse(mcpManageCmd)).mapN { (fmt, action) =>
      fmt.validate match
        case Left(err) => console.errorln(s"Error: $err").as(ExitCode(1))
        case Right(()) => action(fmt)
    }
  }

  override def main: Opts[IO[ExitCode]] = proxyCmd.orElse(listCmd).orElse(mcpCmd)

  // --- proxy & list (unchanged) ---

  private def runProxy(specSource: String, operationIds: List[String]): IO[ExitCode] =
    EmberClientBuilder.default[IO].build.use { client =>
      for
        spec   <- SpecLoader.load(client, specSource)
        baseUrl = spec.servers.headOption
                    .map(_.url)
                    .getOrElse(
                      if specSource.startsWith("http") then
                        val uri = new java.net.URI(specSource)
                        s"${uri.getScheme}://${uri.getHost}" + (if uri.getPort > 0 then s":${uri.getPort}" else "")
                      else "http://localhost"
                    )
        tools   = OpenApiToolBuilder.buildTools(spec, operationIds, client, baseUrl)
        server <- ServerBuilder[IO]("openapi-mcp", BuildInfo.version)
                    .withTools(tools*)
                    .build
        _ <- StdioTransport.run[IO](server)
      yield ExitCode.Success
    }

  private def runList(specSource: String): IO[ExitCode] =
    EmberClientBuilder.default[IO].build.use { client =>
      for
        spec <- SpecLoader.load(client, specSource)
        ops   = OpenApiToolBuilder.listOperationIds(spec)
        _    <- ops.traverse_ { case (id, method, path) =>
               console.errorln(s"  $method\t$path\t$id")
             }
      yield ExitCode.Success
    }

  // --- mcp add ---

  private def runMcpAdd(fmt: ConfigFormat, specSource: String, operationIds: List[String]): IO[ExitCode] =
    EmberClientBuilder.default[IO].build.use { client =>
      for
        spec     <- SpecLoader.load(client, specSource)
        allOps    = OpenApiToolBuilder.listOperationIds(spec)
        allIds    = allOps.map(_._1).toSet
        selected <-
          if operationIds == List("*") then
            val allIdsList = allOps.map(_._1)
            if allIdsList.size > 10 then
              for
                _ <-
                  console.error(s"This will add ${allIdsList.size} endpoints, which may bloat your context window. Are you sure? [y/N] ")
                answer <- console.readLine
                result <- answer.trim.toLowerCase match
                            case "y" | "yes" => IO.pure(Right(allIdsList))
                            case _           => console.errorln("Aborted.").as(Left(ExitCode(1)))
              yield result
            else IO.pure(Right(allIdsList))
          else if operationIds.nonEmpty then
            val missing = operationIds.filterNot(allIds.contains)
            if missing.nonEmpty then
              console
                .errorln(
                  s"Error: unknown operationId(s): ${missing.mkString(", ")}\nAvailable: ${allIds.toList.sorted.mkString(", ")}"
                )
                .as(Left(ExitCode(1)))
            else IO.pure(Right(operationIds))
          else interactivePickOps(allOps).map(Right(_))
        result <- selected match
                    case Left(code)                => IO.pure(code)
                    case Right(ops) if ops.isEmpty =>
                      console.errorln("No operations selected.").as(ExitCode(1))
                    case Right(ops) =>
                      val serverName =
                        McpJsonFile.deriveServerName(specSource, Option(spec.info.title).filter(_.nonEmpty))
                      val fileName = fmt.filePath.toString
                      McpJsonFile.addEntry(fmt, serverName, specSource, ops) *>
                        console
                          .errorln(s"Wrote $fileName server '$serverName' with ${ops.size} operation(s)")
                          .as(ExitCode.Success)
      yield result
    }

  private def interactivePickOps(allOps: List[(String, String, String)]): IO[List[String]] =
    import scala.collection.mutable
    val selected = mutable.Set.empty[Int]

    def printOps: IO[Unit] =
      allOps.zipWithIndex.traverse_ { case ((id, method, path), i) =>
        val marker = if selected.contains(i) then "[x]" else "[ ]"
        console.errorln(s"  ${i + 1}. $marker $method\t$path\t$id")
      }

    def loop: IO[List[String]] =
      for
        _      <- console.errorln("")
        _      <- printOps
        _      <- console.errorln("\nToggle: enter number(s) | all | none | done")
        _      <- console.error("> ")
        line   <- console.readLine
        result <- line.trim.toLowerCase match
                    case "done" | "" =>
                      IO.pure(allOps.zipWithIndex.collect { case ((id, _, _), i) if selected.contains(i) => id })
                    case "all" =>
                      allOps.indices.foreach(selected.add)
                      loop
                    case "none" =>
                      selected.clear()
                      loop
                    case input =>
                      input.split("[,\\s]+").foreach { tok =>
                        tok.toIntOption match
                          case Some(n) if n >= 1 && n <= allOps.size =>
                            if selected.contains(n - 1) then selected.remove(n - 1) else selected.add(n - 1)
                          case _ =>
                            () // ignore invalid
                      }
                      loop
      yield result

    loop

  // --- mcp del ---

  private def runMcpDel(fmt: ConfigFormat, name: String): IO[ExitCode] =
    val fileName = fmt.filePath.toString
    McpJsonFile.deleteEntry(fmt, name).flatMap {
      case true  => console.errorln(s"Removed '$name' from $fileName").as(ExitCode.Success)
      case false => console.errorln(s"Entry '$name' not found in $fileName").as(ExitCode(1))
    }

  // --- mcp manage ---

  private def runMcpManage(fmt: ConfigFormat): IO[ExitCode] =
    val fileName = fmt.filePath.toString

    def loop: IO[ExitCode] =
      for
        entries <- McpJsonFile.listEntries(fmt)
        _       <- console.errorln(s"\n--- $fileName entries ---")
        _       <-
          if entries.isEmpty then console.errorln("  (none)")
          else
            entries.zipWithIndex.traverse_ { case ((name, json), i) =>
              val args    = fmt.parseArgs(json).getOrElse(Nil)
              val spec    = args.drop(1).headOption.getOrElse("?")
              val opCount = args.drop(2).size
              console.errorln(s"  ${i + 1}. $name  ($spec, $opCount ops)")
            }
        _      <- console.errorln("\nCommands: del <number|name> | add <spec> | quit")
        _      <- console.error("> ")
        line   <- console.readLine
        result <- line.trim match
                    case "quit" | "exit" | "q"         => IO.pure(ExitCode.Success)
                    case cmd if cmd.startsWith("del ") =>
                      val arg        = cmd.drop(4).trim
                      val targetName = arg.toIntOption match
                        case Some(n) if n >= 1 && n <= entries.size => Some(entries(n - 1)._1)
                        case None if arg.nonEmpty                   => Some(arg)
                        case _                                      => None
                      targetName match
                        case Some(name) =>
                          McpJsonFile.deleteEntry(fmt, name).flatMap {
                            case true  => console.errorln(s"Removed '$name'")
                            case false => console.errorln(s"Entry '$name' not found")
                          } *> loop
                        case None =>
                          console.errorln("Invalid argument") *> loop
                    case cmd if cmd.startsWith("add ") =>
                      val spec = cmd.drop(4).trim
                      if spec.isEmpty then console.errorln("Usage: add <spec>") *> loop
                      else runMcpAdd(fmt, spec, Nil) *> loop
                    case "" => loop
                    case _  =>
                      console.errorln("Unknown command") *> loop
      yield result

    loop
