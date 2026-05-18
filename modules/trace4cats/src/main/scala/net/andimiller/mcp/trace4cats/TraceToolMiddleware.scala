package net.andimiller.mcp.trace4cats

import cats.Applicative
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*

import net.andimiller.mcp.core.server.ToolCallContext
import net.andimiller.mcp.core.server.ToolMiddleware

import trace4cats.AttributeValue
import trace4cats.SpanStatus
import trace4cats.Trace

/** A [[ToolMiddleware]] that wraps every tool call in a trace4cats span.
  *
  * Span shape per call:
  *   - name: `mcp.tools.call ${request.name}`
  *   - attributes: `mcp.tool.name` (string), `mcp.tool.is_error` (boolean), `mcp.session.id` (string, when present),
  *     plus any supplied by the caller via the `extractor`
  *   - status: `SpanStatus.Internal` on `response.isError`, `SpanStatus.Ok` otherwise
  *
  * Extractor attributes are written INSIDE the span, BEFORE the handler runs — so they're captured even if the handler
  * throws.
  */
object TraceToolMiddleware:

  /** No-extractor form — records only the built-in attributes. */
  def apply[F[_]: MonadCancelThrow: Trace, Ctx]: ToolMiddleware[F, Ctx] =
    apply(_ => Applicative[F].pure(Map.empty))

  /** Extractor form — caller supplies a function that derives additional span attributes per call. The extractor sees
    * the full [[ToolCallContext]] so it can reach `context.request`, `context.sessionId`, `context.ctx`, or
    * `context.resolved.annotations`.
    */
  def apply[F[_]: MonadCancelThrow, Ctx](
      extractor: ToolCallContext[F, Ctx] => F[Map[String, AttributeValue]]
  )(using T: Trace[F]): ToolMiddleware[F, Ctx] = handler =>
    context =>
      T.span(s"mcp.tools.call ${context.request.name}") {
        for
          custom <- extractor(context)
          _      <- T.put("mcp.tool.name", AttributeValue.StringValue(context.request.name))
          _      <- context.sessionId.traverse_(id => T.put("mcp.session.id", AttributeValue.StringValue(id)))
          _      <- custom.toList.traverse_((k, v) => T.put(k, v))
          resp   <- handler(context)
          _      <- T.put("mcp.tool.is_error", AttributeValue.BooleanValue(resp.isError))
          _      <- T.setStatus(if resp.isError then SpanStatus.Internal("tool returned isError=true") else SpanStatus.Ok)
        yield resp
      }
