package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*
import net.andimiller.mcp.core.protocol.*

/**
 * Fluent builder for constructing MCP servers.
 *
 * Example:
 * {{{
 * val server = ServerBuilder[IO]("my-server", "1.0.0")
 *   .withTool(myToolHandler)
 *   .withResource(myResourceHandler)
 *   .enableToolNotifications
 *   .build
 * }}}
 */
class ServerBuilder[F[_]: Async](
  name: String,
  version: String,
  toolHandlers: List[ToolHandler[F]] = Nil,
  resourceHandlers: List[ResourceHandler[F]] = Nil,
  resourceTemplateHandlers: List[ResourceTemplateHandler[F]] = Nil,
  promptHandlers: List[PromptHandler[F]] = Nil,
  toolCapabilities: Option[ToolCapabilities] = None,
  resourceCapabilities: Option[ResourceCapabilities] = None,
  promptCapabilities: Option[PromptCapabilities] = None,
  loggingCapabilities: Option[LoggingCapabilities] = None
):

  /** Add a tool handler to the server */
  def withTool(handler: ToolHandler[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers :+ handler,
      resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities.orElse(Some(ToolCapabilities())),
      resourceCapabilities, promptCapabilities, loggingCapabilities
    )

  /** Add multiple tool handlers to the server */
  def withTools(handlers: ToolHandler[F]*): ServerBuilder[F] =
    handlers.foldLeft(this)((builder, handler) => builder.withTool(handler))

  /** Add a resource handler to the server */
  def withResource(handler: ResourceHandler[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers,
      resourceHandlers :+ handler,
      resourceTemplateHandlers, promptHandlers,
      toolCapabilities,
      resourceCapabilities.orElse(Some(ResourceCapabilities())),
      promptCapabilities, loggingCapabilities
    )

  /** Add a resource template handler to the server */
  def withResourceTemplate(handler: ResourceTemplateHandler[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers,
      resourceHandlers,
      resourceTemplateHandlers :+ handler, promptHandlers,
      toolCapabilities,
      resourceCapabilities.orElse(Some(ResourceCapabilities())),
      promptCapabilities, loggingCapabilities
    )

  /** Add multiple resource template handlers to the server */
  def withResourceTemplates(handlers: ResourceTemplateHandler[F]*): ServerBuilder[F] =
    handlers.foldLeft(this)((builder, handler) => builder.withResourceTemplate(handler))

  /** Add multiple resource handlers to the server */
  def withResources(handlers: ResourceHandler[F]*): ServerBuilder[F] =
    handlers.foldLeft(this)((builder, handler) => builder.withResource(handler))

  /** Add a prompt handler to the server */
  def withPrompt(handler: PromptHandler[F]): ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers,
      promptHandlers :+ handler,
      toolCapabilities, resourceCapabilities,
      promptCapabilities.orElse(Some(PromptCapabilities())),
      loggingCapabilities
    )

  /** Add multiple prompt handlers to the server */
  def withPrompts(handlers: PromptHandler[F]*): ServerBuilder[F] =
    handlers.foldLeft(this)((builder, handler) => builder.withPrompt(handler))

  /** Enable tool list change notifications */
  def enableToolNotifications: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      Some(ToolCapabilities(listChanged = Some(true))),
      resourceCapabilities, promptCapabilities, loggingCapabilities
    )

  /** Enable resource subscriptions */
  def enableResourceSubscriptions: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities,
      Some(ResourceCapabilities(subscribe = Some(true))),
      promptCapabilities, loggingCapabilities
    )

  /** Enable resource list change notifications */
  def enableResourceNotifications: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities,
      Some(resourceCapabilities.getOrElse(ResourceCapabilities()).copy(listChanged = Some(true))),
      promptCapabilities, loggingCapabilities
    )

  /** Enable prompt list change notifications */
  def enablePromptNotifications: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities, resourceCapabilities,
      Some(PromptCapabilities(listChanged = Some(true))),
      loggingCapabilities
    )

  /** Enable logging support */
  def enableLogging: ServerBuilder[F] =
    new ServerBuilder[F](
      name, version,
      toolHandlers, resourceHandlers, resourceTemplateHandlers, promptHandlers,
      toolCapabilities, resourceCapabilities, promptCapabilities,
      Some(LoggingCapabilities())
    )

  /** Build the server */
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
  /** Create a new server builder with the given name and version */
  def apply[F[_]: Async](name: String, version: String): ServerBuilder[F] =
    new ServerBuilder[F](name, version)
