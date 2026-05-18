package net.andimiller.mcp.trace4cats

import cats.Applicative

import net.andimiller.mcp.core.server.ToolCallContext

import trace4cats.TraceHeaders

/** Helpers for pulling trace context out of `ToolCallContext.request._meta`.
  *
  * Pair these with [[EntrypointTraceToolMiddleware]] to continue an upstream trace from MCP `_meta`.
  */
object TraceMetaExtractor:

  /** Pulls W3C `traceparent` (and optional `tracestate`) from `context.request._meta` into a [[TraceHeaders]].
    *
    * When `_meta` is absent or doesn't contain a `traceparent`, returns `TraceHeaders.empty` — which makes
    * `EntryPoint.continueOrElseRoot` fall back to a fresh root span.
    */
  def w3c[F[_]: Applicative, Ctx]: ToolCallContext[F, Ctx] => F[TraceHeaders] = context =>
    val pairs = for
      meta <- context.request._meta.toList
      key  <- List("traceparent", "tracestate")
      raw  <- meta(key).flatMap(_.asString).toList
    yield key -> raw
    Applicative[F].pure(TraceHeaders.of(pairs*))
