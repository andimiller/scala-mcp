package net.andimiller.mcp.http4s

import cats.effect.kernel.Async
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.server.*
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.GZip

class BasicMcpHttpBuilder[F[_]: Async] private[http4s] (
    val mName: String,
    val mVersion: String,
    val mConfig: McpHttpConfig,
    val mTools: Vector[Tool.Resolved[F]],
    val mResources: Vector[McpResource.Resolved[F]],
    val mResourceTemplates: Vector[ResourceTemplate.Resolved[F]],
    val mPrompts: Vector[Prompt.Resolved[F]],
    val mToolCaps: Option[ToolCapabilities],
    val mResourceCaps: Option[ResourceCapabilities],
    val mPromptCaps: Option[PromptCapabilities],
    val mLoggingCaps: Option[LoggingCapabilities]
):

  private def copy(
      mName: String = this.mName,
      mVersion: String = this.mVersion,
      mConfig: McpHttpConfig = this.mConfig,
      mTools: Vector[Tool.Resolved[F]] = this.mTools,
      mResources: Vector[McpResource.Resolved[F]] = this.mResources,
      mResourceTemplates: Vector[ResourceTemplate.Resolved[F]] = this.mResourceTemplates,
      mPrompts: Vector[Prompt.Resolved[F]] = this.mPrompts,
      mToolCaps: Option[ToolCapabilities] = this.mToolCaps,
      mResourceCaps: Option[ResourceCapabilities] = this.mResourceCaps,
      mPromptCaps: Option[PromptCapabilities] = this.mPromptCaps,
      mLoggingCaps: Option[LoggingCapabilities] = this.mLoggingCaps
  ): BasicMcpHttpBuilder[F] =
    new BasicMcpHttpBuilder[F](
      mName, mVersion, mConfig, mTools, mResources, mResourceTemplates, mPrompts,
      mToolCaps, mResourceCaps, mPromptCaps, mLoggingCaps
    )

  // ── Config ──────────────────────────────────────────────────────────

  def name(n: String): BasicMcpHttpBuilder[F] = copy(mName = n)
  def version(v: String): BasicMcpHttpBuilder[F] = copy(mVersion = v)
  def host(h: Host): BasicMcpHttpBuilder[F] = copy(mConfig = mConfig.copy(host = h))
  def port(p: Port): BasicMcpHttpBuilder[F] = copy(mConfig = mConfig.copy(port = p))
  def withExplorer(redirectToRoot: Boolean = false): BasicMcpHttpBuilder[F] =
    copy(mConfig = mConfig.copy(explorerEnabled = true, rootRedirectToExplorer = redirectToRoot))

  // ── Handlers ─────────────────────────────────────────────────────────

  def withTool(tool: Tool.Resolved[F]): BasicMcpHttpBuilder[F] =
    copy(mTools = mTools :+ tool)

  def withTools(tools: Tool.Resolved[F]*): BasicMcpHttpBuilder[F] =
    copy(mTools = mTools ++ tools)

  def withResource(handler: McpResource.Resolved[F]): BasicMcpHttpBuilder[F] =
    copy(mResources = mResources :+ handler)

  def withResource(resource: McpResource[F, Unit]): BasicMcpHttpBuilder[F] =
    withResource(resource.resolve)

  def withResources(handlers: McpResource.Resolved[F]*): BasicMcpHttpBuilder[F] =
    copy(mResources = mResources ++ handlers)

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): BasicMcpHttpBuilder[F] =
    copy(mResourceTemplates = mResourceTemplates :+ handler)

  def withResourceTemplate(rt: ResourceTemplate[F, Unit]): BasicMcpHttpBuilder[F] =
    withResourceTemplate(rt.resolve)

  def withResourceTemplates(handlers: ResourceTemplate.Resolved[F]*): BasicMcpHttpBuilder[F] =
    copy(mResourceTemplates = mResourceTemplates ++ handlers)

  def withPrompt(handler: Prompt.Resolved[F]): BasicMcpHttpBuilder[F] =
    copy(mPrompts = mPrompts :+ handler)

  def withPrompt(prompt: Prompt[F, Unit]): BasicMcpHttpBuilder[F] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): BasicMcpHttpBuilder[F] =
    copy(mPrompts = mPrompts ++ handlers)

  // ── Capabilities ─────────────────────────────────────────────────────

  def enableToolNotifications: BasicMcpHttpBuilder[F] =
    copy(mToolCaps = Some(mToolCaps.getOrElse(ToolCapabilities()).copy(listChanged = Some(true))))

  def enableResourceSubscriptions: BasicMcpHttpBuilder[F] =
    copy(mResourceCaps = Some(mResourceCaps.getOrElse(ResourceCapabilities()).copy(subscribe = Some(true))))

  def enableResourceNotifications: BasicMcpHttpBuilder[F] =
    copy(mResourceCaps = Some(mResourceCaps.getOrElse(ResourceCapabilities()).copy(listChanged = Some(true))))

  def enablePromptNotifications: BasicMcpHttpBuilder[F] =
    copy(mPromptCaps = Some(mPromptCaps.getOrElse(PromptCapabilities()).copy(listChanged = Some(true))))

  def enableLogging: BasicMcpHttpBuilder[F] =
    copy(mLoggingCaps = Some(LoggingCapabilities()))

  // ── Terminal operations ─────────────────────────────────────────────

  def routes: HttpRoutes[F] =
    McpHttp.routes(buildServer)

  private[http4s] def buildServer: F[Server[F]] =
    val capabilities = ServerCapabilities(
      tools = mToolCaps,
      resources = mResourceCaps,
      prompts = mPromptCaps,
      logging = mLoggingCaps
    )
    DefaultServer[F](
      info = Implementation(mName, mVersion),
      capabilities = capabilities,
      toolHandlers = mTools.toList,
      resourceHandlers = mResources.toList,
      resourceTemplateHandlers = mResourceTemplates.toList,
      promptHandlers = mPrompts.toList
    ).widen[Server[F]]

object BasicMcpHttpBuilder:

  extension (builder: BasicMcpHttpBuilder[IO])
    def serve: Resource[IO, org.http4s.server.Server] =
      Resource.eval(builder.buildServer).flatMap { server =>
        McpHttp.serve(server, builder.mConfig)
      }
