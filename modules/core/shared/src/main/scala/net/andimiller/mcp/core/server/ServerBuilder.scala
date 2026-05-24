package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*
import io.circe.Json

import net.andimiller.mcp.core.protocol.*

/** Non-contextual server builder. For stdio / non-streaming HTTP servers the `Ctx` parameter defaults to `Unit` via the
  * `ServerBuilder[F](name, version)` factory in the companion object.
  */
class ServerBuilder[F[_]: Async, Ctx](
    name: String,
    version: String,
    ctx: Ctx,
    toolHandlers: List[Tool[F, Ctx]] = Nil,
    resourceHandlers: List[McpResource.Resolved[F]] = Nil,
    resourceTemplateHandlers: List[ResourceTemplate.Resolved[F]] = Nil,
    promptHandlers: List[Prompt.Resolved[F]] = Nil,
    capabilities: CapabilityTracker = CapabilityTracker.empty,
    title: Option[String] = None,
    description: Option[String] = None,
    icons: List[Icon] = Nil,
    websiteUrl: Option[String] = None,
    toolMiddlewares: List[ToolMiddleware[F, Ctx]] = Nil
):

  private def withCopy(
      capabilities: CapabilityTracker = this.capabilities,
      toolHandlers: List[Tool[F, Ctx]] = this.toolHandlers,
      resourceHandlers: List[McpResource.Resolved[F]] = this.resourceHandlers,
      resourceTemplateHandlers: List[ResourceTemplate.Resolved[F]] = this.resourceTemplateHandlers,
      promptHandlers: List[Prompt.Resolved[F]] = this.promptHandlers,
      title: Option[String] = this.title,
      description: Option[String] = this.description,
      icons: List[Icon] = this.icons,
      websiteUrl: Option[String] = this.websiteUrl,
      toolMiddlewares: List[ToolMiddleware[F, Ctx]] = this.toolMiddlewares
  ): ServerBuilder[F, Ctx] =
    new ServerBuilder[F, Ctx](
      name, version, ctx, toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers, capabilities, title,
      description, icons, websiteUrl, toolMiddlewares
    )

  def withTool(tool: Tool[F, Ctx]): ServerBuilder[F, Ctx] =
    withCopy(toolHandlers = toolHandlers :+ tool, capabilities = capabilities.withToolAdded)

  def withTools(tools: Tool[F, Ctx]*): ServerBuilder[F, Ctx] =
    tools.foldLeft(this)((builder, tool) => builder.withTool(tool))

  def withResource(handler: McpResource.Resolved[F]): ServerBuilder[F, Ctx] =
    withCopy(resourceHandlers = resourceHandlers :+ handler, capabilities = capabilities.withResourceAdded)

  def withResource(resource: McpResource[F, Unit]): ServerBuilder[F, Ctx] =
    withResource(resource.resolve)

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): ServerBuilder[F, Ctx] =
    withCopy(
      resourceTemplateHandlers = resourceTemplateHandlers :+ handler,
      capabilities = capabilities.withResourceAdded
    )

  def withResourceTemplate(rt: ResourceTemplate[F, Unit]): ServerBuilder[F, Ctx] =
    withResourceTemplate(rt.resolve)

  def withResourceTemplates(handlers: ResourceTemplate.Resolved[F]*): ServerBuilder[F, Ctx] =
    handlers.foldLeft(this)((builder, handler) => builder.withResourceTemplate(handler))

  def withResources(handlers: McpResource.Resolved[F]*): ServerBuilder[F, Ctx] =
    handlers.foldLeft(this)((builder, handler) => builder.withResource(handler))

  def withPrompt(handler: Prompt.Resolved[F]): ServerBuilder[F, Ctx] =
    withCopy(promptHandlers = promptHandlers :+ handler, capabilities = capabilities.withPromptAdded)

  def withPrompt(prompt: Prompt[F, Unit]): ServerBuilder[F, Ctx] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): ServerBuilder[F, Ctx] =
    handlers.foldLeft(this)((builder, handler) => builder.withPrompt(handler))

  def enableToolNotifications: ServerBuilder[F, Ctx] =
    withCopy(capabilities = capabilities.withToolNotifications)

  def enableResourceSubscriptions: ServerBuilder[F, Ctx] =
    withCopy(capabilities = capabilities.withResourceSubscriptions)

  def enableResourceNotifications: ServerBuilder[F, Ctx] =
    withCopy(capabilities = capabilities.withResourceNotifications)

  def enablePromptNotifications: ServerBuilder[F, Ctx] =
    withCopy(capabilities = capabilities.withPromptNotifications)

  def enableLogging: ServerBuilder[F, Ctx] =
    withCopy(capabilities = capabilities.withLogging)

  /** Declare an MCP extension capability under `capabilities.extensions[key]`. The `value` is the extension-specific
    * configuration document. Multiple extensions compose without trampling.
    */
  def withExtension(key: String, value: Json): ServerBuilder[F, Ctx] =
    withCopy(capabilities = capabilities.withExtension(key, value))

  def withTitle(t: String): ServerBuilder[F, Ctx] = withCopy(title = Some(t))

  def withDescription(d: String): ServerBuilder[F, Ctx] = withCopy(description = Some(d))

  def withIcon(i: Icon): ServerBuilder[F, Ctx] = withCopy(icons = icons :+ i)

  def withIcons(xs: List[Icon]): ServerBuilder[F, Ctx] = withCopy(icons = xs)

  def withWebsiteUrl(url: String): ServerBuilder[F, Ctx] = withCopy(websiteUrl = Some(url))

  /** Append a server-wide tool middleware. Composed around every tool call, OUTSIDE any per-tool middleware. First
    * registered = outermost.
    */
  def withToolMiddleware(mw: ToolMiddleware[F, Ctx]): ServerBuilder[F, Ctx] =
    withCopy(toolMiddlewares = toolMiddlewares :+ mw)

  def build: F[Server[F]] =
    val info = Implementation(
      name = name,
      version = version,
      title = title,
      description = description,
      icons = Option.when(icons.nonEmpty)(icons),
      websiteUrl = websiteUrl
    )

    DefaultServer[F, Ctx](
      info = info, capabilities = capabilities.toServerCapabilities, ctx = ctx, toolHandlers = toolHandlers,
      resourceHandlers = resourceHandlers, resourceTemplateHandlers = resourceTemplateHandlers,
      promptHandlers = promptHandlers, toolMiddlewares = toolMiddlewares, sessionId = None
    ).widen[Server[F]]

object ServerBuilder:

  def apply[F[_]: Async](name: String, version: String): ServerBuilder[F, Unit] =
    new ServerBuilder[F, Unit](name, version, ())

  def contextual[F[_]: Async, Ctx](name: String, version: String, ctx: Ctx): ServerBuilder[F, Ctx] =
    new ServerBuilder[F, Ctx](name, version, ctx)
