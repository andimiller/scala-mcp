package net.andimiller.mcp.http4s

import cats.effect.kernel.Ref

import net.andimiller.mcp.core.server.ClientChannel
import net.andimiller.mcp.core.server.RequestHandler

/** State for a single MCP client session over HTTP.
  *
  * Each session has its own request handler, client channel (notifications + server-initiated requests), and
  * subscription set.
  */
case class McpSession[F[_]](
    id: String,
    handler: RequestHandler[F],
    clientChannel: ClientChannel[F],
    subscriptions: Ref[F, Set[String]]
)
