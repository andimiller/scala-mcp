package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.all.*
import fs2.Stream
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.transport.MessageChannel

/**
 * Core MCP server interface.
 *
 * A server provides tools, resources, and prompts to clients.
 */
trait Server[F[_]]:
  /** Server implementation information */
  def info: Implementation

  /** Server capabilities */
  def capabilities: ServerCapabilities

  /** List all available tools */
  def listTools(request: ListToolsRequest): F[ListToolsResponse]

  /** Call a tool */
  def callTool(request: CallToolRequest): F[CallToolResponse]

  /** List all available resources */
  def listResources(request: ListResourcesRequest): F[ListResourcesResponse]

  /** Read a resource */
  def readResource(request: ReadResourceRequest): F[ReadResourceResponse]

  /** List resource templates */
  def listResourceTemplates(request: ListResourceTemplatesRequest): F[ListResourceTemplatesResponse]

  /** Subscribe to resource updates */
  def subscribe(request: SubscribeRequest): F[Unit]

  /** Unsubscribe from resource updates */
  def unsubscribe(request: UnsubscribeRequest): F[Unit]

  /** List all available prompts */
  def listPrompts(request: ListPromptsRequest): F[ListPromptsResponse]

  /** Get a prompt */
  def getPrompt(request: GetPromptRequest): F[GetPromptResponse]

  /** Handle ping request */
  def ping(): F[Unit]

/**
 * Server session that processes messages over a transport channel.
 *
 * Delegates inbound message handling to [[RequestHandler]] and forwards any server-initiated
 * notifications/requests published to the [[ClientChannel]] back onto the transport. Inbound
 * and outbound streams are merged so the same fiber drives both directions.
 */
class ServerSession[F[_]: Async](
  server: Server[F],
  channel: MessageChannel[F],
  clientChannel: ClientChannel[F]
):
  private val handler = new RequestHandler[F](server, clientChannel.requester)

  /**
   * Run the server session, processing incoming messages and forwarding server-initiated
   * outbound traffic until the underlying transport closes.
   *
   * Inbound handling uses `parEvalMapUnordered` so a tool that awaits a server-initiated
   * response (e.g. `elicitation/create`) does not block the inbound reader from accepting
   * the very response it's waiting for — a deadlock that would otherwise be unavoidable
   * with sequential `evalMap`.
   */
  def run: F[Unit] =
    val maxConcurrent = 16
    val inbound: Stream[F, Unit] =
      channel.incoming.parEvalMapUnordered(maxConcurrent) { message =>
        handler.handle(message).flatMap {
          case Some(response) => channel.send(response)
          case None           => Async[F].unit
        }
      }
    val outbound: Stream[F, Unit] =
      clientChannel.subscribe.evalMap(channel.send)

    inbound.merge(outbound).compile.drain
