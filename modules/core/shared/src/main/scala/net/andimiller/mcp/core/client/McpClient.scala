package net.andimiller.mcp.core.client

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.Message

import fs2.Stream
import io.circe.Json

/** Default protocol version this library negotiates. Tracked alongside the server's `RequestHandler.handleInitialize`
  * which advertises the same value.
  */
object McpProtocol:

  val DefaultVersion: String = "2025-11-25"

/** A connected-but-not-initialised MCP client.
  *
  * Returned by transport factories (`StdioMcpClient.spawn`, `StdioMcpClient.fromStreams`,
  * `StreamableHttpMcpClient.fromHttpClient`). Performs the JSON-RPC `initialize` handshake on demand and yields a
  * fully-typed [[McpClient]] carrying the negotiated values. Use the high-level builders if you want this step done for
  * you.
  */
trait UninitializedMcpClient[F[_]]:

  /** Perform the JSON-RPC `initialize` request, then send `notifications/initialized`, then yield a typed `McpClient`.
    *
    * Calling more than once is undefined — the spec allows only one initialize per session.
    */
  def initialize(
      info: Implementation,
      capabilities: ClientCapabilities = ClientCapabilities.empty,
      protocolVersion: String = McpProtocol.DefaultVersion
  ): F[McpClient[F]]

/** A fully-initialised MCP client. The presence of an `McpClient` is proof the handshake succeeded — `serverInfo`,
  * `serverCapabilities` and `protocolVersion` are plain values, not effects or `Option`s.
  */
trait McpClient[F[_]]:

  /** Server identity received during `initialize`. */
  def serverInfo: Implementation

  /** Capabilities the server advertised during `initialize`. */
  def serverCapabilities: ServerCapabilities

  /** Protocol version the server confirmed during `initialize`. */
  def protocolVersion: String

  // ── Liveness ──────────────────────────────────────────────────────

  /** Send a `ping` request. */
  def ping(): F[Unit]

  // ── Tools ─────────────────────────────────────────────────────────

  def listTools(cursor: Option[String] = None): F[ListToolsResponse]

  def callTool(request: CallToolRequest): F[CallToolResponse]

  /** Convenience: build a `CallToolRequest` from name + arguments. */
  def callTool(name: String, arguments: Json): F[CallToolResponse]

  // ── Resources ─────────────────────────────────────────────────────

  def listResources(cursor: Option[String] = None): F[ListResourcesResponse]

  def readResource(uri: String): F[ReadResourceResponse]

  def listResourceTemplates(cursor: Option[String] = None): F[ListResourceTemplatesResponse]

  def subscribe(uri: String): F[Unit]

  def unsubscribe(uri: String): F[Unit]

  // ── Prompts ───────────────────────────────────────────────────────

  def listPrompts(cursor: Option[String] = None): F[ListPromptsResponse]

  def getPrompt(name: String, arguments: Map[String, Json] = Map.empty): F[GetPromptResponse]

  // ── Inbound from server ───────────────────────────────────────────

  /** Stream of server-initiated notifications (e.g. `notifications/tools/list_changed`,
    * `notifications/resources/updated`).
    *
    * Implementations multicast: multiple subscribers see every notification. Backpressure is the implementation's
    * responsibility.
    */
  def notifications: Stream[F, Message.Notification]
