package net.andimiller.mcp.core.server

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*
import net.andimiller.mcp.core.protocol.*

/**
 * Default implementation of the MCP Server trait.
 *
 * This implementation stores handlers in maps and delegates to them when requests arrive.
 */
class DefaultServer[F[_]: Async](
  val info: Implementation,
  val capabilities: ServerCapabilities,
  toolHandlers: Map[String, Tool.Resolved[F]],
  resourceHandlers: Map[String, McpResource.Resolved[F]],
  resourceTemplateHandlers: List[ResourceTemplate.Resolved[F]],
  promptHandlers: Map[String, Prompt.Resolved[F]],
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

  override def callTool(request: CallToolRequest, rc: RequestContext[F]): F[CallToolResponse] =
    toolHandlers.get(request.name) match
      case Some(handler) =>
        handler.handle(request.arguments, rc).map { result =>
          CallToolResponse(content = result.content, structuredContent = result.structuredContent, isError = result.isError)
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
        // Fall back to resource templates
        resourceTemplateHandlers.iterator.map(_.read(request.uri)).collectFirst { case Some(f) => f } match
          case Some(readF) =>
            readF.map(content => ReadResourceResponse(contents = List(content)))
          case None =>
            Async[F].raiseError(new Exception(s"Resource not found: ${request.uri}"))

  override def listResourceTemplates(request: ListResourceTemplatesRequest): F[ListResourceTemplatesResponse] =
    val templates = resourceTemplateHandlers.map { handler =>
      ResourceTemplateDefinition(
        uriTemplate = handler.uriTemplate,
        name = handler.name,
        description = handler.description,
        mimeType = handler.mimeType
      )
    }
    ListResourceTemplatesResponse(resourceTemplates = templates, nextCursor = None).pure[F]

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
    toolHandlers: List[Tool.Resolved[F]] = Nil,
    resourceHandlers: List[McpResource.Resolved[F]] = Nil,
    resourceTemplateHandlers: List[ResourceTemplate.Resolved[F]] = Nil,
    promptHandlers: List[Prompt.Resolved[F]] = Nil
  ): F[DefaultServer[F]] =
    for
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
    yield new DefaultServer[F](
      info = info,
      capabilities = capabilities,
      toolHandlers = toolHandlers.map(h => h.name -> h).toMap,
      resourceHandlers = resourceHandlers.map(h => h.uri -> h).toMap,
      resourceTemplateHandlers = resourceTemplateHandlers,
      promptHandlers = promptHandlers.map(h => h.name -> h).toMap,
      subscriptions = subscriptions
    )
