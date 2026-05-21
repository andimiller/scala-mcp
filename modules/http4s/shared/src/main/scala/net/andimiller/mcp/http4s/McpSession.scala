package net.andimiller.mcp.http4s

import cats.effect.kernel.Ref

import net.andimiller.mcp.core.server.ClientChannel
import net.andimiller.mcp.core.server.RequestHandler

/** State for a single MCP client session over HTTP.
  *
  * Each session has its own request handler, client channel (notifications + server-initiated requests), and
  * subscription set.
  *
  * `cleanup` is invoked when the session is removed (DELETE /mcp, or out-of-band store eviction). Use it to cancel
  * background fibers spawned for this session — e.g. the dynamic-tool visibility watcher.
  */
case class McpSession[F[_]](
    id: String,
    handler: RequestHandler[F],
    clientChannel: ClientChannel[F],
    subscriptions: Ref[F, Set[String]],
    cleanup: F[Unit]
)
