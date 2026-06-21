package net.andimiller.mcp.http4s

import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.server.*

import com.comcast.ip4s.*
import org.http4s.*

/** Non-streaming HTTP MCP server builder.
  *
  * Parameterised on `Ctx` so per-tool/per-server middleware can carry a typed context. For most users `Ctx = Unit` is
  * the right choice; the `BasicMcpHttpBuilder[F](name, version)` factory in the companion picks `Unit` automatically.
  */
class BasicMcpHttpBuilder[F[_]: Async: org.typelevel.log4cats.LoggerFactory, Ctx] private[http4s] (
    val mName: String,
    val mVersion: String,
    val mConfig: McpHttpConfig,
    val mCtx: Ctx,
    val mTools: Vector[Tool[F, Ctx]],
    val mResources: Vector[McpResource.Resolved[F]],
    val mResourceTemplates: Vector[ResourceTemplate.Resolved[F]],
    val mPrompts: Vector[Prompt.Resolved[F]],
    val mCaps: CapabilityTracker,
    val mTitle: Option[String] = None,
    val mDescription: Option[String] = None,
    val mIcons: List[Icon] = Nil,
    val mWebsiteUrl: Option[String] = None,
    val mExtraRoutes: HttpRoutes[F],
    val mToolMiddlewares: List[ToolMiddleware[F, Ctx]] = Nil
):

  private def copy(
      mName: String = this.mName,
      mVersion: String = this.mVersion,
      mConfig: McpHttpConfig = this.mConfig,
      mTools: Vector[Tool[F, Ctx]] = this.mTools,
      mResources: Vector[McpResource.Resolved[F]] = this.mResources,
      mResourceTemplates: Vector[ResourceTemplate.Resolved[F]] = this.mResourceTemplates,
      mPrompts: Vector[Prompt.Resolved[F]] = this.mPrompts,
      mCaps: CapabilityTracker = this.mCaps,
      mTitle: Option[String] = this.mTitle,
      mDescription: Option[String] = this.mDescription,
      mIcons: List[Icon] = this.mIcons,
      mWebsiteUrl: Option[String] = this.mWebsiteUrl,
      mExtraRoutes: HttpRoutes[F] = this.mExtraRoutes,
      mToolMiddlewares: List[ToolMiddleware[F, Ctx]] = this.mToolMiddlewares
  ): BasicMcpHttpBuilder[F, Ctx] =
    new BasicMcpHttpBuilder[F, Ctx](
      mName, mVersion, mConfig, mCtx, mTools, mResources, mResourceTemplates, mPrompts, mCaps, mTitle, mDescription,
      mIcons, mWebsiteUrl, mExtraRoutes, mToolMiddlewares
    )

  // ── Config ──────────────────────────────────────────────────────────

  def name(n: String): BasicMcpHttpBuilder[F, Ctx] = copy(mName = n)

  def version(v: String): BasicMcpHttpBuilder[F, Ctx] = copy(mVersion = v)

  def host(h: Host): BasicMcpHttpBuilder[F, Ctx] = copy(mConfig = mConfig.copy(host = h))

  def port(p: Port): BasicMcpHttpBuilder[F, Ctx] = copy(mConfig = mConfig.copy(port = p))

  def withExplorer(redirectToRoot: Boolean = false): BasicMcpHttpBuilder[F, Ctx] =
    copy(mConfig = mConfig.copy(explorerEnabled = true, rootRedirectToExplorer = redirectToRoot))

  // ── Implementation metadata ────────────────────────────────────────

  def title(t: String): BasicMcpHttpBuilder[F, Ctx] = copy(mTitle = Some(t))

  def description(d: String): BasicMcpHttpBuilder[F, Ctx] = copy(mDescription = Some(d))

  def icon(i: Icon): BasicMcpHttpBuilder[F, Ctx] = copy(mIcons = mIcons :+ i)

  def icons(xs: List[Icon]): BasicMcpHttpBuilder[F, Ctx] = copy(mIcons = xs)

  def websiteUrl(url: String): BasicMcpHttpBuilder[F, Ctx] = copy(mWebsiteUrl = Some(url))

  /** Mount additional HTTP routes alongside the MCP routes (e.g. `/icon.svg`, `/favicon.ico`, health checks). Composes
    * with existing extras via `<+>`, so this can be called multiple times.
    */
  def withRoutes(extra: HttpRoutes[F]): BasicMcpHttpBuilder[F, Ctx] =
    copy(mExtraRoutes = mExtraRoutes <+> extra)

  /** Append a server-wide tool middleware. Composed around every tool call, OUTSIDE any per-tool middleware. */
  def withToolMiddleware(mw: ToolMiddleware[F, Ctx]): BasicMcpHttpBuilder[F, Ctx] =
    copy(mToolMiddlewares = mToolMiddlewares :+ mw)

  // ── Handlers ─────────────────────────────────────────────────────────

  def withTool(tool: Tool[F, Ctx]): BasicMcpHttpBuilder[F, Ctx] =
    copy(mTools = mTools :+ tool, mCaps = mCaps.withToolAdded)

  def withTools(tools: Tool[F, Ctx]*): BasicMcpHttpBuilder[F, Ctx] =
    copy(mTools = mTools ++ tools, mCaps = if tools.nonEmpty then mCaps.withToolAdded else mCaps)

  def withResource(handler: McpResource.Resolved[F]): BasicMcpHttpBuilder[F, Ctx] =
    copy(mResources = mResources :+ handler, mCaps = mCaps.withResourceAdded)

  def withResource(resource: McpResource[F, Unit]): BasicMcpHttpBuilder[F, Ctx] =
    withResource(resource.resolve)

  def withResources(handlers: McpResource.Resolved[F]*): BasicMcpHttpBuilder[F, Ctx] =
    copy(mResources = mResources ++ handlers, mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps)

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): BasicMcpHttpBuilder[F, Ctx] =
    copy(mResourceTemplates = mResourceTemplates :+ handler, mCaps = mCaps.withResourceAdded)

  def withResourceTemplate(rt: ResourceTemplate[F, Unit]): BasicMcpHttpBuilder[F, Ctx] =
    withResourceTemplate(rt.resolve)

  def withResourceTemplates(handlers: ResourceTemplate.Resolved[F]*): BasicMcpHttpBuilder[F, Ctx] =
    copy(
      mResourceTemplates = mResourceTemplates ++ handlers,
      mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps
    )

  def withPrompt(handler: Prompt.Resolved[F]): BasicMcpHttpBuilder[F, Ctx] =
    copy(mPrompts = mPrompts :+ handler, mCaps = mCaps.withPromptAdded)

  def withPrompt(prompt: Prompt[F, Unit]): BasicMcpHttpBuilder[F, Ctx] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): BasicMcpHttpBuilder[F, Ctx] =
    copy(mPrompts = mPrompts ++ handlers, mCaps = if handlers.nonEmpty then mCaps.withPromptAdded else mCaps)

  // ── Capabilities ─────────────────────────────────────────────────────

  def enableToolNotifications: BasicMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withToolNotifications)

  def enableResourceSubscriptions: BasicMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withResourceSubscriptions)

  def enableResourceNotifications: BasicMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withResourceNotifications)

  def enablePromptNotifications: BasicMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withPromptNotifications)

  def enableLogging: BasicMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withLogging)

  // ── Terminal operations ─────────────────────────────────────────────

  def routes: HttpRoutes[F] =
    McpHttp.routes(buildServer) <+> mExtraRoutes

  private[http4s] def buildServer: F[Server[F]] =
    DefaultServer[F, Ctx](
      info = Implementation(
        name = mName,
        version = mVersion,
        title = mTitle,
        description = mDescription,
        icons = Option.when(mIcons.nonEmpty)(mIcons),
        websiteUrl = mWebsiteUrl
      ),
      capabilities = mCaps.toServerCapabilities,
      ctx = mCtx,
      toolHandlers = mTools.toList,
      resourceHandlers = mResources.toList,
      resourceTemplateHandlers = mResourceTemplates.toList,
      promptHandlers = mPrompts.toList,
      toolMiddlewares = mToolMiddlewares,
      sessionId = None
    ).widen[Server[F]]

object BasicMcpHttpBuilder:

  extension [Ctx](builder: BasicMcpHttpBuilder[IO, Ctx])

    def serve(using org.typelevel.log4cats.LoggerFactory[IO]): Resource[IO, org.http4s.server.Server] =
      Resource.eval(builder.buildServer).flatMap { server =>
        McpHttp.serve(server, builder.mConfig)
      }
