package net.andimiller.mcp.examples.harness

import cats.effect.kernel.Async
import cats.effect.std.Console
import cats.syntax.all.*

import net.andimiller.mcp.core.client.ClientHandler
import net.andimiller.mcp.core.protocol.ClientCapabilities
import net.andimiller.mcp.core.protocol.ElicitationCapabilities
import net.andimiller.mcp.core.protocol.FormElicitationCapability
import net.andimiller.mcp.core.protocol.SamplingCapabilities
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import io.circe.Json

/** Builds the harness's `ClientHandler` (sampling + elicitation) and its matching `ClientCapabilities` advertisement.
  * The handler is shared across every connected MCP server.
  *
  * Currently logs every dispatched method on stderr to make routing issues visible — when the harness is exercised
  * against a real elicitation/sampling-using server, these breadcrumbs are the fastest way to confirm the request
  * actually reached the client.
  */
object ClientHandlers:

  /** Capabilities the harness advertises during `initialize` so servers know they can call back. */
  val capabilities: ClientCapabilities = ClientCapabilities(
    sampling = Some(SamplingCapabilities()),
    elicitation = Some(ElicitationCapabilities(form = Some(FormElicitationCapability())))
  )

  def build[F[_]: Async: Console](llm: OpenAiClient[F], modelLabel: String): ClientHandler[F] =
    new ClientHandler[F]:

      def handle(method: String, id: RequestId, params: Option[Json]): F[Either[JsonRpcError, Json]] =
        Console[F].errorln(
          Theme.info(
            s"client-handler request: method='$method' params=${params.map(_.noSpaces.take(200)).getOrElse("(none)")}"
          )
        ) *> {
          method match
            case "sampling/createMessage" =>
              SamplingHandler.handle[F](llm, modelLabel)(id, params)
            case "elicitation/create" =>
              ElicitationHandler.handle[F](id, params)
            case other =>
              Console[F]
                .errorln(Theme.err(s"client-handler: no handler for method '$other' — returning MethodNotFound"))
                .as(Left(JsonRpcError.methodNotFound(other)))
        }

      def handleNotification(method: String, params: Option[Json]): F[Unit] =
        // Notifications are also surfaced via the topic-backed `client.notifications` stream which
        // the Notifications fiber prints; no need to log them twice here.
        Async[F].unit
