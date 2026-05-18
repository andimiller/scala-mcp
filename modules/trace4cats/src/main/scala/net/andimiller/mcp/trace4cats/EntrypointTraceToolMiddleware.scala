package net.andimiller.mcp.trace4cats

import cats.Applicative
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*

import net.andimiller.mcp.core.server.ToolCallContext
import net.andimiller.mcp.core.server.ToolMiddleware

import trace4cats.AttributeValue
import trace4cats.EntryPoint
import trace4cats.ErrorHandler
import trace4cats.Span
import trace4cats.SpanKind
import trace4cats.SpanStatus
import trace4cats.TraceHeaders
import trace4cats.context.Local

/** A [[ToolMiddleware]] that opens a span at the MCP boundary, continuing an upstream trace if the request carries one.
  *
  * Use this when callers send trace context via `request._meta` (typically a W3C `traceparent`). The middleware:
  *
  *   1. Calls the supplied `headers` extractor to derive a `TraceHeaders` from the request (usually pulling from
  *      `_meta`). Returning `TraceHeaders.empty` makes the span a fresh root.
  *   2. Uses `EntryPoint.continueOrElseRoot` to open either a continuation span or a fresh root.
  *   3. Installs the new span as the ambient context via the supplied `Local[F, Span[F]]` so any downstream `Trace[F]`
  *      (e.g. one backed by `IOLocal`) sees it.
  *   4. Records `mcp.tool.name`, `mcp.session.id` (when present), any extractor-supplied attributes, then runs the
  *      handler, then sets `mcp.tool.is_error` and `SpanStatus` based on the response.
  *
  * If you don't need cross-system propagation, prefer [[TraceToolMiddleware]] — it's simpler and only requires
  * `Trace[F]`.
  */
object EntrypointTraceToolMiddleware:

  /** No-attribute-extractor form. */
  def apply[F[_]: MonadCancelThrow, Ctx](
      entryPoint: EntryPoint[F],
      local: Local[F, Span[F]],
      headers: ToolCallContext[F, Ctx] => F[TraceHeaders]
  ): ToolMiddleware[F, Ctx] =
    apply(entryPoint, local, headers, _ => Applicative[F].pure(Map.empty))

  /** Full form — extra attributes are added to the span before the handler runs. Both extractors receive the full
    * [[ToolCallContext]] so they can pull from `request._meta`, `sessionId`, `ctx`, or `resolved.annotations`.
    */
  def apply[F[_]: MonadCancelThrow, Ctx](
      entryPoint: EntryPoint[F],
      local: Local[F, Span[F]],
      headers: ToolCallContext[F, Ctx] => F[TraceHeaders],
      extractor: ToolCallContext[F, Ctx] => F[Map[String, AttributeValue]]
  ): ToolMiddleware[F, Ctx] = handler =>
    context =>
      headers(context).flatMap { hdrs =>
        entryPoint
          .continueOrElseRoot(s"mcp.tools.call ${context.request.name}", SpanKind.Server, hdrs, ErrorHandler.empty)
          .use { span =>
            local.scope(
              for
                custom <- extractor(context)
                _      <- span.put("mcp.tool.name", AttributeValue.StringValue(context.request.name))
                _      <- context.sessionId.traverse_(id => span.put("mcp.session.id", AttributeValue.StringValue(id)))
                _      <- span.putAll(custom)
                resp   <- handler(context)
                _      <- span.put("mcp.tool.is_error", AttributeValue.BooleanValue(resp.isError))
                _      <- span.setStatus(
                       if resp.isError then SpanStatus.Internal("tool returned isError=true")
                       else SpanStatus.Ok
                     )
              yield resp
            )(span)
          }
      }
