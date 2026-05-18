package net.andimiller.mcp.core.server

import net.andimiller.mcp.core.protocol.CallToolRequest
import net.andimiller.mcp.core.protocol.CallToolResponse

/** Everything middleware can see about an in-flight tool call.
  *
  * @param request
  *   the wire-level `CallToolRequest` (carries `name`, `arguments`, `_meta`).
  * @param sessionId
  *   the session id when the server is being driven by a session-aware transport (streaming HTTP). `None` for stdio and
  *   the basic non-streaming HTTP transport.
  * @param resolved
  *   the tool being invoked. Gives middleware access to `title`, `annotations`, `execution`, `_meta` etc. without
  *   re-looking-up by name.
  * @param ctx
  *   the server's `Ctx` value (per-session for streaming HTTP, `Unit` for non-contextual servers). Lets middleware read
  *   tenant/user/correlation info that's in scope inside the handler.
  */
case class ToolCallContext[F[_], Ctx](
    request: CallToolRequest,
    sessionId: Option[String],
    resolved: Tool[F, Ctx],
    ctx: Ctx
)

/** A tool-call handler — the unit middleware wraps. */
type ToolHandler[F[_], Ctx] = ToolCallContext[F, Ctx] => F[CallToolResponse]

/** Composable wrapper around a `ToolHandler`. Server-wide middlewares and per-tool middlewares share the same `Ctx` —
  * for stdio / non-contextual servers, that `Ctx` is `Unit`.
  *
  * Composition: server-wide middlewares wrap per-tool middlewares wrap the handler. Within each level, the first
  * registered is the outermost — request flows outer→inner, response flows inner→outer.
  */
type ToolMiddleware[F[_], Ctx] = ToolHandler[F, Ctx] => ToolHandler[F, Ctx]

object ToolMiddleware:

  /** The no-op middleware — useful as a base when folding a list of middlewares. */
  def identity[F[_], Ctx]: ToolMiddleware[F, Ctx] = h => h

  /** Compose a list of middlewares so the head is outermost: `composeAll(List(a, b, c))(handler)` produces
    * `a(b(c(handler)))`. Folding right preserves first-registered-is-outermost ordering.
    */
  def composeAll[F[_], Ctx](mws: List[ToolMiddleware[F, Ctx]]): ToolMiddleware[F, Ctx] = handler =>
    mws.foldRight(handler)((mw, acc) => mw(acc))
