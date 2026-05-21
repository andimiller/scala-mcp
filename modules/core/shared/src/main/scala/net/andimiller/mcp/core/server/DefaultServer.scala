package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*

/** Default implementation of the MCP Server trait.
  *
  * Parameterised on the server's `Ctx` (typed at the session level for streaming HTTP, `Unit` for stdio / non-
  * contextual servers). The `Ctx` value is threaded into each [[ToolCallContext]] so middleware and handlers can read
  * it. Per-tool middleware is read directly off each [[Tool]] at dispatch time and composed INSIDE the server-wide
  * [[toolMiddlewares]].
  */
class DefaultServer[F[_]: Async, Ctx](
    val info: Implementation,
    val capabilities: ServerCapabilities,
    toolHandlers: Map[String, Tool[F, Ctx]],
    resourceHandlers: Map[String, McpResource.Resolved[F]],
    resourceTemplateHandlers: List[ResourceTemplate.Resolved[F]],
    promptHandlers: Map[String, Prompt.Resolved[F]],
    subscriptions: Ref[F, Set[String]],
    toolMiddlewares: List[ToolMiddleware[F, Ctx]],
    sessionId: Option[String],
    ctx: Ctx
) extends Server[F]:

  override def listTools(request: ListToolsRequest): F[ListToolsResponse] =
    toolHandlers.values.toList
      .filterA(t => t.visible.fold(true.pure[F])(_(ctx)))
      .map { visible =>
        val tools = visible.map { tool =>
          ToolDefinition(
            name = tool.name,
            description = Some(tool.description),
            inputSchema = tool.inputSchema,
            outputSchema = tool.outputSchema,
            title = tool.title,
            icons = Option.when(tool.icons.nonEmpty)(tool.icons),
            annotations = tool.annotations,
            execution = tool.execution,
            _meta = tool.meta
          )
        }
        ListToolsResponse(tools = tools, nextCursor = None)
      }

  override def callTool(request: CallToolRequest): F[CallToolResponse] =
    toolHandlers.get(request.name) match
      case Some(tool) =>
        tool.visible.fold(true.pure[F])(_(ctx)).flatMap {
          case false => Async[F].raiseError(new Exception(s"Tool not found: ${request.name}"))
          case true  =>
            val callCtx                   = ToolCallContext(request, sessionId, tool, ctx)
            val base: ToolHandler[F, Ctx] = c => c.resolved.handle(c)
            val withPerTool               = ToolMiddleware.composeAll(tool.middleware)(base)
            val composed                  = ToolMiddleware.composeAll(toolMiddlewares)(withPerTool)
            composed(callCtx)
        }
      case None       => Async[F].raiseError(new Exception(s"Tool not found: ${request.name}"))

  override def listResources(request: ListResourcesRequest): F[ListResourcesResponse] =
    val resources = resourceHandlers.values.map { handler =>
      ResourceDefinition(
        uri = handler.uri,
        name = handler.name,
        description = handler.description,
        mimeType = handler.mimeType,
        title = handler.title,
        icons = Option.when(handler.icons.nonEmpty)(handler.icons),
        annotations = handler.annotations,
        size = handler.size,
        _meta = handler.meta
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
        mimeType = handler.mimeType,
        title = handler.title,
        icons = Option.when(handler.icons.nonEmpty)(handler.icons),
        annotations = handler.annotations,
        _meta = handler.meta
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
        arguments = handler.arguments,
        title = handler.title,
        icons = Option.when(handler.icons.nonEmpty)(handler.icons),
        _meta = handler.meta
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

  /** Create a new contextual DefaultServer. */
  def apply[F[_]: Async, Ctx](
      info: Implementation,
      capabilities: ServerCapabilities,
      ctx: Ctx,
      toolHandlers: List[Tool[F, Ctx]],
      resourceHandlers: List[McpResource.Resolved[F]],
      resourceTemplateHandlers: List[ResourceTemplate.Resolved[F]],
      promptHandlers: List[Prompt.Resolved[F]],
      toolMiddlewares: List[ToolMiddleware[F, Ctx]],
      sessionId: Option[String]
  ): F[DefaultServer[F, Ctx]] =
    for subscriptions <- Ref.of[F, Set[String]](Set.empty)
    yield new DefaultServer[F, Ctx](
      info = info,
      capabilities = capabilities,
      ctx = ctx,
      toolMiddlewares = toolMiddlewares,
      sessionId = sessionId,
      toolHandlers = toolHandlers.map(h => h.name -> h).toMap,
      resourceHandlers = resourceHandlers.map(h => h.uri -> h).toMap,
      resourceTemplateHandlers = resourceTemplateHandlers,
      promptHandlers = promptHandlers.map(h => h.name -> h).toMap,
      subscriptions = subscriptions
    )

  /** Minimal `Ctx = Unit` factory — keeps existing test callers compiling without churn. Use the contextual factory
    * above if you need a non-Unit `Ctx` or any middleware.
    */
  def apply[F[_]: Async](
      info: Implementation,
      capabilities: ServerCapabilities,
      toolHandlers: List[Tool[F, Unit]] = Nil,
      resourceHandlers: List[McpResource.Resolved[F]] = Nil,
      resourceTemplateHandlers: List[ResourceTemplate.Resolved[F]] = Nil,
      promptHandlers: List[Prompt.Resolved[F]] = Nil,
      toolMiddlewares: List[ToolMiddleware[F, Unit]] = Nil,
      sessionId: Option[String] = None
  ): F[DefaultServer[F, Unit]] =
    apply[F, Unit](
      info = info, capabilities = capabilities, ctx = (), toolHandlers = toolHandlers,
      resourceHandlers = resourceHandlers, resourceTemplateHandlers = resourceTemplateHandlers,
      promptHandlers = promptHandlers, toolMiddlewares = toolMiddlewares, sessionId = sessionId
    )
