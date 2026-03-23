package net.andimiller.mcp.http4s

import cats.effect.kernel.Ref
import net.andimiller.mcp.core.server.{NotificationSink, RequestHandler}

/**
 * State for a single MCP client session over HTTP.
 *
 * Each session has its own request handler, notification sink, and subscription set.
 */
case class McpSession[F[_]](
  id: String,
  handler: RequestHandler[F],
  sink: NotificationSink[F],
  subscriptions: Ref[F, Set[String]]
)
