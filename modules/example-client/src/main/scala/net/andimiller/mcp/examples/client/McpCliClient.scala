package net.andimiller.mcp.examples.client

import cats.data.Validated
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Console
import cats.syntax.all.*

import net.andimiller.mcp.core.client.McpClient
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.http4s.StreamableHttpMcpClient
import net.andimiller.mcp.stdio.StdioMcpClient

import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import io.circe.Json
import io.circe.parser.parse
import org.http4s.Header
import org.http4s.Headers
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString

/** Tiny interactive REPL over an MCP server reachable via stdio or streamable HTTP.
  *
  * Usage:
  *   - `mcp-client stdio <command> [args...]` — spawn `command` and talk to it over stdio
  *   - `mcp-client http <url> [-H key=value ...]` — connect to a streamable-HTTP MCP server
  *
  * REPL commands: `t` (tools), `r` (resources), `p` (prompts), `q` (quit). Each fetches the list, numbers the entries,
  * lets you pick one, and invokes it.
  */
object McpCliClient
    extends CommandIOApp(
      name = "mcp-client",
      header = "Interactive MCP client over stdio or HTTP",
      version = "0.1.0"
    ):

  // ── CLI definitions ────────────────────────────────────────────────

  private val clientInfo = Implementation("mcp-example-client", "0.1.0")

  private val stdioCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("stdio", "Spawn a subprocess as an MCP server over stdio") {
      (
        Opts.argument[String](metavar = "command"),
        Opts.arguments[String](metavar = "args").orNone
      ).mapN { (command, args) =>
        runRepl(stdioResource(command, args.map(_.toList).getOrElse(Nil)))
      }
    }

  private val urlOpt: Opts[Uri] =
    Opts
      .argument[String](metavar = "url")
      .mapValidated { s =>
        Uri.fromString(s).fold(t => Validated.invalidNel(s"invalid URL '$s': ${t.getMessage}"), Validated.valid(_))
      }

  private val headerOpt: Opts[Headers] =
    Opts
      .options[String]("header", "Extra HTTP header in 'name: value' form (repeatable)", short = "H")
      .orEmpty
      .mapValidated { raws =>
        raws.traverse { raw =>
          raw.split(":", 2) match
            case Array(k, v) => Validated.valid(Header.Raw(CIString(k.trim), v.trim))
            case _           => Validated.invalidNel(s"invalid header '$raw' (expected 'name: value')")
        }.map(Headers(_))
      }

  private val noSseOpt: Opts[Boolean] =
    Opts.flag("no-sse", "Don't open the SSE stream for server-initiated traffic").orFalse.map(!_)

  private val httpCmd: Opts[IO[ExitCode]] =
    Opts.subcommand("http", "Connect to an MCP server over streamable HTTP") {
      (urlOpt, headerOpt, noSseOpt).mapN { (uri, headers, openSse) =>
        runRepl(httpResource(uri, headers, openSse))
      }
    }

  override def main: Opts[IO[ExitCode]] = stdioCmd.orElse(httpCmd)

  // ── Transport wiring ───────────────────────────────────────────────

  private def stdioResource(command: String, args: List[String]): Resource[IO, McpClient[IO]] =
    StdioMcpClient
      .builder[IO]
      .withCommand(command, args)
      .withInfo(clientInfo)
      .connect

  private def httpResource(uri: Uri, headers: Headers, openSse: Boolean): Resource[IO, McpClient[IO]] =
    EmberClientBuilder.default[IO].build.flatMap { httpClient =>
      StreamableHttpMcpClient
        .builder[IO](httpClient, uri)
        .withHeaders(headers)
        .withSse(openSse)
        .withInfo(clientInfo)
        .connect
    }

  // ── REPL ───────────────────────────────────────────────────────────

  private def runRepl(client: Resource[IO, McpClient[IO]]): IO[ExitCode] =
    client.use { c =>
      printBanner(c) *> repl(c)
    }
      .as(ExitCode.Success)
      .handleErrorWith(t => Console[IO].errorln(s"client failed: ${t.getMessage}").as(ExitCode.Error))

  private def printBanner(client: McpClient[IO]): IO[Unit] =
    val caps = capabilitiesSummary(client.serverCapabilities)
    Console[IO].println(
      s"""Connected to ${client.serverInfo.name} v${client.serverInfo.version} (protocol ${client.protocolVersion})
         |Server capabilities: $caps""".stripMargin
    )

  private def capabilitiesSummary(caps: ServerCapabilities): String =
    List(
      caps.tools.as("tools"),
      caps.resources.as("resources"),
      caps.prompts.as("prompts"),
      caps.logging.as("logging")
    ).flatten match
      case Nil => "(none)"
      case xs  => xs.mkString(", ")

  private def repl(client: McpClient[IO]): IO[Unit] =
    val loop: IO[Unit] = for
      _      <- Console[IO].println("")
      _      <- Console[IO].print("[t]ools | [r]esources | [p]rompts | [q]uit > ")
      choice <- Console[IO].readLine.map(_.trim.toLowerCase)
      _      <- choice match
             case "t" | "tools"         => browseTools(client)
             case "r" | "resources"     => browseResources(client)
             case "p" | "prompts"       => browsePrompts(client)
             case "q" | "quit" | "exit" => Console[IO].println("bye!") *> IO.raiseError(new QuitException)
             case ""                    => IO.unit
             case other                 => Console[IO].println(s"unknown choice: '$other' (try t, r, p, or q)")
    yield ()
    loop.foreverM.recover { case _: QuitException => () }

  // ── tools ─────────────────────────────────────────────────────────

  private def browseTools(client: McpClient[IO]): IO[Unit] =
    client.listTools().attempt.flatMap {
      case Left(err)   => Console[IO].errorln(s"listTools failed: ${err.getMessage}")
      case Right(resp) =>
        val tools = resp.tools
        if tools.isEmpty then Console[IO].println("(no tools)")
        else
          for
            _   <- numbered(tools)(t => s"${t.name} — ${t.description}")
            sel <- pickIndex(tools.size)
            _   <- sel.traverse_(i => callTool(client, tools(i)))
          yield ()
    }

  private def callTool(client: McpClient[IO], tool: ToolDefinition): IO[Unit] =
    for
      _    <- Console[IO].println(s"\nTool: ${tool.name}")
      _    <- Console[IO].println(s"Input schema: ${tool.inputSchema.spaces2}")
      args <- promptJson("Arguments JSON (empty for {})")
      _    <- args match
             case Some(json) =>
               client.callTool(tool.name, json).attempt.flatMap {
                 case Left(err)   => Console[IO].errorln(s"callTool failed: ${err.getMessage}")
                 case Right(resp) => printToolResponse(resp)
               }
             case None =>
               Console[IO].println("(skipped — invalid JSON)")
    yield ()

  private def printToolResponse(resp: CallToolResponse): IO[Unit] =
    Console[IO].println(s"\n  isError: ${resp.isError}") *>
      resp.content.zipWithIndex.traverse_ { case (c, i) => Console[IO].println(s"  [$i] ${renderContent(c)}") } *>
      resp.structuredContent.traverse_(s => Console[IO].println(s"  structured: ${s.spaces2}"))

  private def renderContent(c: Content): String = c match
    case Content.Text(text, _, _)                 => s"text: $text"
    case Content.Image(_, mimeType, _, _)         => s"image ($mimeType)"
    case Content.Audio(_, mimeType, _, _)         => s"audio ($mimeType)"
    case Content.Resource(uri, mt, text, _, _, _) =>
      val body = text.getOrElse("(blob)")
      s"resource $uri${mt.map(t => s" [$t]").getOrElse("")}: $body"
    case Content.ResourceLink(uri, name, _, _, mt, _, _, _, _) =>
      s"resource link $name → $uri${mt.map(t => s" [$t]").getOrElse("")}"

  // ── resources ─────────────────────────────────────────────────────

  private def browseResources(client: McpClient[IO]): IO[Unit] =
    client.listResources().attempt.flatMap {
      case Left(err)   => Console[IO].errorln(s"listResources failed: ${err.getMessage}")
      case Right(resp) =>
        val resources = resp.resources
        if resources.isEmpty then Console[IO].println("(no resources)")
        else
          for
            _   <- numbered(resources)(r => s"${r.uri} — ${r.name}${r.description.map(d => s" ($d)").getOrElse("")}")
            sel <- pickIndex(resources.size)
            _   <- sel.traverse_(i => readResource(client, resources(i)))
          yield ()
    }

  private def readResource(client: McpClient[IO], resource: ResourceDefinition): IO[Unit] =
    client.readResource(resource.uri).attempt.flatMap {
      case Left(err)   => Console[IO].errorln(s"readResource failed: ${err.getMessage}")
      case Right(resp) =>
        Console[IO].println(s"\nResource: ${resource.uri}") *>
          resp.contents.traverse_ { c =>
            val body = c.text.orElse(c.blob.map(b => s"<base64 ${b.length} chars>")).getOrElse("(empty)")
            Console[IO].println(s"  ${c.mimeType.getOrElse("?")}: $body")
          }
    }

  // ── prompts ───────────────────────────────────────────────────────

  private def browsePrompts(client: McpClient[IO]): IO[Unit] =
    client.listPrompts().attempt.flatMap {
      case Left(err)   => Console[IO].errorln(s"listPrompts failed: ${err.getMessage}")
      case Right(resp) =>
        val prompts = resp.prompts
        if prompts.isEmpty then Console[IO].println("(no prompts)")
        else
          for
            _   <- numbered(prompts)(p => s"${p.name}${p.description.map(d => s" — $d").getOrElse("")}")
            sel <- pickIndex(prompts.size)
            _   <- sel.traverse_(i => getPrompt(client, prompts(i)))
          yield ()
    }

  private def getPrompt(client: McpClient[IO], prompt: PromptDefinition): IO[Unit] =
    for
      _    <- Console[IO].println(s"\nPrompt: ${prompt.name}")
      args <- prompt.arguments.foldLeftM(Map.empty[String, Json]) { (acc, arg) =>
                val label = if arg.required then s"${arg.name}*" else arg.name
                val hint  = arg.description.fold("")(d => s" ($d)")
                Console[IO].print(s"  $label$hint: ") *>
                  Console[IO].readLine.map(_.trim).map { v =>
                    if v.isEmpty then acc else acc.updated(arg.name, Json.fromString(v))
                  }
              }
      _ <- client.getPrompt(prompt.name, args).attempt.flatMap {
             case Left(err)   => Console[IO].errorln(s"getPrompt failed: ${err.getMessage}")
             case Right(resp) =>
               resp.description.traverse_(d => Console[IO].println(s"\nDescription: $d")) *>
                 resp.messages.zipWithIndex.traverse_ { case (m, i) =>
                   Console[IO].println(s"  [$i ${m.role}] ${renderContent(m.content)}")
                 }
           }
    yield ()

  // ── helpers ───────────────────────────────────────────────────────

  private def numbered[A](items: List[A])(show: A => String): IO[Unit] =
    items.zipWithIndex.traverse_ { case (a, i) =>
      Console[IO].println(f"  ${i + 1}%2d. ${show(a)}")
    }

  private def pickIndex(max: Int): IO[Option[Int]] =
    Console[IO].print(s"pick 1..$max (or 'b' to go back): ") *>
      Console[IO].readLine.map(_.trim).flatMap { line =>
        if line.equalsIgnoreCase("b") || line.isEmpty then IO.pure(None)
        else
          line.toIntOption match
            case Some(n) if n >= 1 && n <= max => IO.pure(Some(n - 1))
            case _                             =>
              Console[IO].println(s"invalid: '$line'") *> IO.pure(None)
      }

  private def promptJson(label: String): IO[Option[Json]] =
    Console[IO].print(s"$label: ") *>
      Console[IO].readLine.map(_.trim).flatMap { line =>
        if line.isEmpty then IO.pure(Some(Json.obj()))
        else
          parse(line) match
            case Right(j) => IO.pure(Some(j))
            case Left(e)  => Console[IO].errorln(s"invalid JSON: ${e.getMessage}").as(None)
      }

  final private class QuitException extends RuntimeException
