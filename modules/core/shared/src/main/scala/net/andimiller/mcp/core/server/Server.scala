package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.all.*
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
 * Delegates message handling to [[RequestHandler]] and bridges responses to the transport.
 */
class ServerSession[F[_]: Async](
  server: Server[F],
  channel: MessageChannel[F]
):
  private val handler = new RequestHandler[F](server)

  /**
   * Run the server session, processing incoming messages until the channel closes.
   */
  def run: F[Unit] =
    channel.incoming
      .evalMap { message =>
        handler.handle(message).flatMap {
          case Some(response) => channel.send(response)
          case None           => Async[F].unit
        }
      }
      .compile
      .drain
