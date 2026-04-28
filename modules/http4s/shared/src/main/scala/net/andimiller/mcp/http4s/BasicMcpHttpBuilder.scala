package net.andimiller.mcp.http4s

import cats.effect.kernel.Async
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.server.*
import org.http4s.*

class BasicMcpHttpBuilder[F[_]: Async] private[http4s] (
    val mName: String,
    val mVersion: String,
    val mConfig: McpHttpConfig,
    val mTools: Vector[Tool.Resolved[F]],
    val mResources: Vector[McpResource.Resolved[F]],
    val mResourceTemplates: Vector[ResourceTemplate.Resolved[F]],
    val mPrompts: Vector[Prompt.Resolved[F]],
    val mCaps: CapabilityTracker
):

  private def copy(
      mName: String = this.mName,
      mVersion: String = this.mVersion,
      mConfig: McpHttpConfig = this.mConfig,
      mTools: Vector[Tool.Resolved[F]] = this.mTools,
      mResources: Vector[McpResource.Resolved[F]] = this.mResources,
      mResourceTemplates: Vector[ResourceTemplate.Resolved[F]] = this.mResourceTemplates,
      mPrompts: Vector[Prompt.Resolved[F]] = this.mPrompts,
      mCaps: CapabilityTracker = this.mCaps
  ): BasicMcpHttpBuilder[F] =
    new BasicMcpHttpBuilder[F](
      mName, mVersion, mConfig, mTools, mResources, mResourceTemplates, mPrompts, mCaps
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
    copy(mTools = mTools :+ tool, mCaps = mCaps.withToolAdded)

  def withTools(tools: Tool.Resolved[F]*): BasicMcpHttpBuilder[F] =
    copy(mTools = mTools ++ tools, mCaps = if tools.nonEmpty then mCaps.withToolAdded else mCaps)

  def withResource(handler: McpResource.Resolved[F]): BasicMcpHttpBuilder[F] =
    copy(mResources = mResources :+ handler, mCaps = mCaps.withResourceAdded)

  def withResource(resource: McpResource[F, Unit]): BasicMcpHttpBuilder[F] =
    withResource(resource.resolve)

  def withResources(handlers: McpResource.Resolved[F]*): BasicMcpHttpBuilder[F] =
    copy(mResources = mResources ++ handlers, mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps)

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): BasicMcpHttpBuilder[F] =
    copy(mResourceTemplates = mResourceTemplates :+ handler, mCaps = mCaps.withResourceAdded)

  def withResourceTemplate(rt: ResourceTemplate[F, Unit]): BasicMcpHttpBuilder[F] =
    withResourceTemplate(rt.resolve)

  def withResourceTemplates(handlers: ResourceTemplate.Resolved[F]*): BasicMcpHttpBuilder[F] =
    copy(
      mResourceTemplates = mResourceTemplates ++ handlers,
      mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps
    )

  def withPrompt(handler: Prompt.Resolved[F]): BasicMcpHttpBuilder[F] =
    copy(mPrompts = mPrompts :+ handler, mCaps = mCaps.withPromptAdded)

  def withPrompt(prompt: Prompt[F, Unit]): BasicMcpHttpBuilder[F] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): BasicMcpHttpBuilder[F] =
    copy(mPrompts = mPrompts ++ handlers, mCaps = if handlers.nonEmpty then mCaps.withPromptAdded else mCaps)

  // ── Capabilities ─────────────────────────────────────────────────────

  def enableToolNotifications: BasicMcpHttpBuilder[F] =
    copy(mCaps = mCaps.withToolNotifications)

  def enableResourceSubscriptions: BasicMcpHttpBuilder[F] =
    copy(mCaps = mCaps.withResourceSubscriptions)

  def enableResourceNotifications: BasicMcpHttpBuilder[F] =
    copy(mCaps = mCaps.withResourceNotifications)

  def enablePromptNotifications: BasicMcpHttpBuilder[F] =
    copy(mCaps = mCaps.withPromptNotifications)

  def enableLogging: BasicMcpHttpBuilder[F] =
    copy(mCaps = mCaps.withLogging)

  // ── Terminal operations ─────────────────────────────────────────────

  def routes: HttpRoutes[F] =
    McpHttp.routes(buildServer)

  private[http4s] def buildServer: F[Server[F]] =
    DefaultServer[F](
      info = Implementation(mName, mVersion),
      capabilities = mCaps.toServerCapabilities,
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
