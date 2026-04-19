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
    capabilities: CapabilityTracker = CapabilityTracker.empty
):

  def withTool(handler: Tool.Resolved[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers :+ handler,
      resourceHandlers, resourceTemplateHandlers, promptHandlers,
      capabilities.withToolAdded
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
      capabilities.withResourceAdded
    )

  def withResource(resource: McpResource[F, Unit]): ServerBuilder[F] =
    withResource(resource.resolve)

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers,
      resourceHandlers,
      resourceTemplateHandlers :+ handler, promptHandlers,
      capabilities.withResourceAdded
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
      capabilities.withPromptAdded
    )

  def withPrompt(prompt: Prompt[F, Unit]): ServerBuilder[F] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): ServerBuilder[F] =
    handlers.foldLeft(this)((builder, handler) => builder.withPrompt(handler))

  def enableToolNotifications: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      capabilities.withToolNotifications
    )

  def enableResourceSubscriptions: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      capabilities.withResourceSubscriptions
    )

  def enableResourceNotifications: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      capabilities.withResourceNotifications
    )

  def enablePromptNotifications: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      capabilities.withPromptNotifications
    )

  def enableLogging: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      capabilities.withLogging
    )

  def build: F[Server[F]] =
    val info = Implementation(name, version)

    DefaultServer[F](
      info = info,
      capabilities = capabilities.toServerCapabilities,
      toolHandlers = toolHandlers,
      resourceHandlers = resourceHandlers,
      resourceTemplateHandlers = resourceTemplateHandlers,
      promptHandlers = promptHandlers
    ).widen[Server[F]]

object ServerBuilder:
  def apply[F[_]: Async](name: String, version: String): ServerBuilder[F] =
    new ServerBuilder[F](name, version)
