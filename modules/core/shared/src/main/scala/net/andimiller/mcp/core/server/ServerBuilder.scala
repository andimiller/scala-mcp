package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*
import net.andimiller.mcp.core.protocol.*

class ServerBuilder[F[_]: Async](
    name: String,
    version: String,
    toolHandlers: List[Tool.Resolved[F]] = Nil,
    resourceHandlers: List[McpResource.Resolved[F]] = Nil,
    resourceTemplateHandlers: List[ResourceTemplate.Resolved[F]] = Nil,
    promptHandlers: List[Prompt.Resolved[F]] = Nil,
    toolCapabilities: Option[ToolCapabilities] = None,
    resourceCapabilities: Option[ResourceCapabilities] = None,
    promptCapabilities: Option[PromptCapabilities] = None,
    loggingCapabilities: Option[LoggingCapabilities] = None
):

  def withTool(handler: Tool.Resolved[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers :+ handler,
      resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities.orElse(Some(ToolCapabilities())),
      resourceCapabilities, promptCapabilities, loggingCapabilities
    )

  def withTool[A, R](tool: Tool[F, Unit, A, R])(using Async[F]): ServerBuilder[F] =
    withTool(tool.provide(()))

  def withTools(handlers: Tool.Resolved[F]*): ServerBuilder[F] =
    handlers.foldLeft(this)((builder, handler) => builder.withTool(handler))

  def withResource(handler: McpResource.Resolved[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers,
      resourceHandlers :+ handler,
      resourceTemplateHandlers, promptHandlers,
      toolCapabilities,
      resourceCapabilities.orElse(Some(ResourceCapabilities())),
      promptCapabilities, loggingCapabilities
    )

  def withResource(resource: McpResource[F, Unit]): ServerBuilder[F] =
    withResource(resource.resolve)

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers,
      resourceHandlers,
      resourceTemplateHandlers :+ handler, promptHandlers,
      toolCapabilities,
      resourceCapabilities.orElse(Some(ResourceCapabilities())),
      promptCapabilities, loggingCapabilities
    )

  def withResourceTemplate(rt: ResourceTemplate[F, Unit]): ServerBuilder[F] =
    withResourceTemplate(rt.resolve)

  def withResourceTemplates(handlers: ResourceTemplate.Resolved[F]*): ServerBuilder[F] =
    handlers.foldLeft(this)((builder, handler) => builder.withResourceTemplate(handler))

  def withResources(handlers: McpResource.Resolved[F]*): ServerBuilder[F] =
    handlers.foldLeft(this)((builder, handler) => builder.withResource(handler))

  def withPrompt(handler: Prompt.Resolved[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers,
      promptHandlers :+ handler,
      toolCapabilities, resourceCapabilities,
      promptCapabilities.orElse(Some(PromptCapabilities())),
      loggingCapabilities
    )

  def withPrompt(prompt: Prompt[F, Unit]): ServerBuilder[F] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): ServerBuilder[F] =
    handlers.foldLeft(this)((builder, handler) => builder.withPrompt(handler))

  def enableToolNotifications: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      Some(ToolCapabilities(listChanged = Some(true))),
      resourceCapabilities, promptCapabilities, loggingCapabilities
    )

  def enableResourceSubscriptions: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities,
      Some(ResourceCapabilities(subscribe = Some(true))),
      promptCapabilities, loggingCapabilities
    )

  def enableResourceNotifications: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities,
      Some(resourceCapabilities.getOrElse(ResourceCapabilities()).copy(listChanged = Some(true))),
      promptCapabilities, loggingCapabilities
    )

  def enablePromptNotifications: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities, resourceCapabilities,
      Some(PromptCapabilities(listChanged = Some(true))),
      loggingCapabilities
    )

  def enableLogging: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities, resourceCapabilities, promptCapabilities,
      Some(LoggingCapabilities())
    )

  def build: F[Server[F]] =
    val info = Implementation(name, version)
    val capabilities = ServerCapabilities(
      tools = toolCapabilities,
      resources = resourceCapabilities,
      prompts = promptCapabilities,
      logging = loggingCapabilities
    )

    DefaultServer[F](
      info = info,
      capabilities = capabilities,
      toolHandlers = toolHandlers,
      resourceHandlers = resourceHandlers,
      resourceTemplateHandlers = resourceTemplateHandlers,
      promptHandlers = promptHandlers
    ).widen[Server[F]]

object ServerBuilder:
  def apply[F[_]: Async](name: String, version: String): ServerBuilder[F] =
    new ServerBuilder[F](name, version)
