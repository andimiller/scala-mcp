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
  def callTool(request: CallToolRequest, rc: RequestContext[F]): F[CallToolResponse]

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
 * Delegates message handling to [[RequestHandler]] and bridges responses to the transport.
 *
 * Request handling runs concurrently (bounded by `maxConcurrent`) so that notifications —
 * most importantly `notifications/cancelled` — can interrupt in-flight requests.
 * Pass `maxConcurrent = 1` if you need the legacy strictly-sequential semantics.
 */
class ServerSession[F[_]: Async](
  server: Server[F],
  channel: MessageChannel[F],
  sink: NotificationSink[F],
  maxConcurrent: Int
):
  /**
   * Secondary constructor for the common case where the transport doesn't support
   * server-initiated notifications (e.g., stdio with no outbound notification multiplexer).
   * Equivalent to [[ServerSession.apply(server, channel)]].
   */
  def this(server: Server[F], channel: MessageChannel[F]) =
    this(server, channel, NotificationSink.noop[F], ServerSession.DefaultMaxConcurrent)

  /**
   * Run the server session, processing incoming messages until the channel closes.
   */
  def run: F[Unit] =
    for
      registry <- CancellationRegistry.create[F]
      handler   = new RequestHandler[F](server, sink, registry)
      _        <- channel.incoming
        .parEvalMapUnordered(maxConcurrent) { message =>
          handler.handle(message).flatMap {
            case Some(response) => channel.send(response)
            case None           => Async[F].unit
          }
        }
        .compile
        .drain
    yield ()

object ServerSession:

  /** Default bound on concurrent request handling per session. */
  val DefaultMaxConcurrent: Int = 64

  /**
   * Create a new session with the default concurrency bound and a no-op notification sink.
   * Progress notifications from tools will be silently dropped; use [[apply]] with a live sink
   * if the transport supports server-initiated notifications.
   */
  def apply[F[_]: Async](server: Server[F], channel: MessageChannel[F]): ServerSession[F] =
    new ServerSession[F](server, channel, NotificationSink.noop[F], DefaultMaxConcurrent)

  /** Create a session with a live notification sink. */
  def apply[F[_]: Async](
    server: Server[F],
    channel: MessageChannel[F],
    sink: NotificationSink[F]
  ): ServerSession[F] =
    new ServerSession[F](server, channel, sink, DefaultMaxConcurrent)

  /** Create a session with a live notification sink and explicit concurrency bound. */
  def apply[F[_]: Async](
    server: Server[F],
    channel: MessageChannel[F],
    sink: NotificationSink[F],
    maxConcurrent: Int
  ): ServerSession[F] =
    new ServerSession[F](server, channel, sink, maxConcurrent)

  /**
   * Create a session that processes messages strictly sequentially (legacy behavior).
   * Note: cancellation notifications will not interrupt in-flight work under this mode,
   * because they can't be dequeued until the in-flight work completes.
   */
  def sequential[F[_]: Async](
    server: Server[F],
    channel: MessageChannel[F]
  ): ServerSession[F] =
    new ServerSession[F](server, channel, NotificationSink.noop[F], 1)

  def sequential[F[_]: Async](
    server: Server[F],
    channel: MessageChannel[F],
    sink: NotificationSink[F]
  ): ServerSession[F] =
    new ServerSession[F](server, channel, sink, 1)
