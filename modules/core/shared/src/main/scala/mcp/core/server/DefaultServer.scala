package mcp.core.server

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*
import mcp.core.protocol.*

/**
 * Default implementation of the MCP Server trait.
 *
 * This implementation stores handlers in maps and delegates to them when requests arrive.
 */
class DefaultServer[F[_]: Async](
  val info: Implementation,
  val capabilities: ServerCapabilities,
  toolHandlers: Map[String, ToolHandler[F]],
  resourceHandlers: Map[String, ResourceHandler[F]],
  promptHandlers: Map[String, PromptHandler[F]],
  subscriptions: Ref[F, Set[String]]
) extends Server[F]:

  override def listTools(request: ListToolsRequest): F[ListToolsResponse] =
    val tools = toolHandlers.values.map { handler =>
      ToolDefinition(
        name = handler.name,
        description = handler.description,
        inputSchema = handler.inputSchema,
        outputSchema = handler.outputSchema
      )
    }.toList

    ListToolsResponse(tools = tools, nextCursor = None).pure[F]

  override def callTool(request: CallToolRequest): F[CallToolResponse] =
    toolHandlers.get(request.name) match
      case Some(handler) =>
        handler.handle(request.arguments).map { result =>
          CallToolResponse(content = result.content, isError = result.isError)
        }
      case None =>
        Async[F].raiseError(new Exception(s"Tool not found: ${request.name}"))

  override def listResources(request: ListResourcesRequest): F[ListResourcesResponse] =
    val resources = resourceHandlers.values.map { handler =>
      ResourceDefinition(
        uri = handler.uri,
        name = handler.name,
        description = handler.description,
        mimeType = handler.mimeType
      )
    }.toList

    ListResourcesResponse(resources = resources, nextCursor = None).pure[F]

  override def readResource(request: ReadResourceRequest): F[ReadResourceResponse] =
    resourceHandlers.get(request.uri) match
      case Some(handler) =>
        handler.read().map { content =>
          ReadResourceResponse(contents = List(content))
        }
      case None =>
        Async[F].raiseError(new Exception(s"Resource not found: ${request.uri}"))

  override def subscribe(request: SubscribeRequest): F[Unit] =
    subscriptions.update(_ + request.uri)

  override def unsubscribe(request: UnsubscribeRequest): F[Unit] =
    subscriptions.update(_ - request.uri)

  override def listPrompts(request: ListPromptsRequest): F[ListPromptsResponse] =
    val prompts = promptHandlers.values.map { handler =>
      PromptDefinition(
        name = handler.name,
        description = handler.description,
        arguments = handler.arguments
      )
    }.toList

    ListPromptsResponse(prompts = prompts, nextCursor = None).pure[F]

  override def getPrompt(request: GetPromptRequest): F[GetPromptResponse] =
    promptHandlers.get(request.name) match
      case Some(handler) =>
        handler.get(request.arguments)
      case None =>
        Async[F].raiseError(new Exception(s"Prompt not found: ${request.name}"))

  override def ping(): F[Unit] =
    Async[F].unit

object DefaultServer:
  /**
   * Create a new DefaultServer with the given configuration.
   */
  def apply[F[_]: Async](
    info: Implementation,
    capabilities: ServerCapabilities,
    toolHandlers: List[ToolHandler[F]] = Nil,
    resourceHandlers: List[ResourceHandler[F]] = Nil,
    promptHandlers: List[PromptHandler[F]] = Nil
  ): F[DefaultServer[F]] =
    for
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
    yield new DefaultServer[F](
      info = info,
      capabilities = capabilities,
      toolHandlers = toolHandlers.map(h => h.name -> h).toMap,
      resourceHandlers = resourceHandlers.map(h => h.uri -> h).toMap,
      promptHandlers = promptHandlers.map(h => h.name -> h).toMap,
      subscriptions = subscriptions
    )
