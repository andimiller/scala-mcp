package net.andimiller.mcp.examples.harness

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all.*

import net.andimiller.mcp.core.client.ClientHandler
import net.andimiller.mcp.core.client.McpClient
import net.andimiller.mcp.core.protocol.ClientCapabilities
import net.andimiller.mcp.core.protocol.Implementation
import net.andimiller.mcp.http4s.StreamableHttpMcpClient
import net.andimiller.mcp.stdio.StdioMcpClient

import org.http4s.Header
import org.http4s.Headers
import org.http4s.Uri
import org.http4s.client.Client
import org.typelevel.ci.CIString

/** Open the MCP clients listed in a `.mcp.json`, returning a name→client map. Resource cleanup shuts every client down
  * (sends DELETE for HTTP sessions, signals EOF on stdio child stdin).
  *
  * The same [[ClientHandler]] and [[ClientCapabilities]] are passed to every client so any server we open can call back
  * via `sampling/createMessage`, `elicitation/create`, etc.
  */
object McpClients:

  def open[F[_]: Async](
      spec: McpServerSpec,
      info: Implementation,
      capabilities: ClientCapabilities,
      handler: ClientHandler[F],
      httpClient: Client[F]
  ): Resource[F, McpClient[F]] = spec match
    case McpServerSpec.Stdio(command, args, env) =>
      StdioMcpClient
        .builder[F]
        .withCommand(command, args)
        .withEnv(env)
        .withInfo(info)
        .withCapabilities(capabilities)
        .withHandler(handler)
        .connect
    case McpServerSpec.Http(url, headers) =>
      Resource
        .eval(
          Async[F]
            .fromEither(Uri.fromString(url).leftMap(t => new RuntimeException(s"bad url '$url': ${t.getMessage}")))
        )
        .flatMap { uri =>
          val hs = Headers(headers.toList.map { case (k, v) => Header.Raw(CIString(k), v) })
          StreamableHttpMcpClient
            .builder[F](httpClient, uri)
            .withHeaders(hs)
            .withInfo(info)
            .withCapabilities(capabilities)
            .withHandler(handler)
            .connect
        }

  def openAll[F[_]: Async](
      cfg: McpJsonConfig,
      info: Implementation,
      capabilities: ClientCapabilities,
      handler: ClientHandler[F],
      httpClient: Client[F]
  ): Resource[F, Map[String, McpClient[F]]] =
    cfg.mcpServers.toList.traverse { case (name, spec) =>
      open(spec, info, capabilities, handler, httpClient).map(name -> _)
    }
      .map(_.toMap)
