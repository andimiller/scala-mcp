package net.andimiller.mcp.http4s

import cats.Eq
import cats.effect.kernel.Async
import cats.effect.{IO, Resource}
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import com.comcast.ip4s.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.server.*
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.GZip

type Append[A, B] = B match
  case Unit => A
  case _    => (A, B)

class StreamingMcpHttpBuilder[F[_]: Async, Ctx] private[http4s] (
    val mName: String,
    val mVersion: String,
    val mConfig: McpHttpConfig,
    val mAuthInfo: Option[StreamingMcpHttpBuilder.AuthInfo[F]],
    val mStatefulCreators: Vector[NotificationSink[F] => F[Any]],
    val mAuthExtractor: Option[Request[F] => F[Option[Any]]],
    val mPlainTools: Vector[Tool.Resolved[F]],
    val mContextToolResolvers: Vector[Any => Tool.Resolved[F]],
    val mPlainResources: Vector[McpResource.Resolved[F]],
    val mContextResourceResolvers: Vector[Any => McpResource.Resolved[F]],
    val mPlainResourceTemplates: Vector[ResourceTemplate.Resolved[F]],
    val mContextResourceTemplateResolvers: Vector[Any => ResourceTemplate.Resolved[F]],
    val mPlainPrompts: Vector[Prompt.Resolved[F]],
    val mContextPromptResolvers: Vector[Any => Prompt.Resolved[F]],
    val mToolCaps: Option[ToolCapabilities],
    val mResourceCaps: Option[ResourceCapabilities],
    val mPromptCaps: Option[PromptCapabilities],
    val mLoggingCaps: Option[LoggingCapabilities]
):

  private def copy[Ctx2](
      mName: String = this.mName,
      mVersion: String = this.mVersion,
      mConfig: McpHttpConfig = this.mConfig,
      mAuthInfo: Option[StreamingMcpHttpBuilder.AuthInfo[F]] = this.mAuthInfo,
      mStatefulCreators: Vector[NotificationSink[F] => F[Any]] = this.mStatefulCreators,
      mAuthExtractor: Option[Request[F] => F[Option[Any]]] = this.mAuthExtractor,
      mPlainTools: Vector[Tool.Resolved[F]] = this.mPlainTools,
      mContextToolResolvers: Vector[Any => Tool.Resolved[F]] = this.mContextToolResolvers,
      mPlainResources: Vector[McpResource.Resolved[F]] = this.mPlainResources,
      mContextResourceResolvers: Vector[Any => McpResource.Resolved[F]] = this.mContextResourceResolvers,
      mPlainResourceTemplates: Vector[ResourceTemplate.Resolved[F]] = this.mPlainResourceTemplates,
      mContextResourceTemplateResolvers: Vector[Any => ResourceTemplate.Resolved[F]] = this.mContextResourceTemplateResolvers,
      mPlainPrompts: Vector[Prompt.Resolved[F]] = this.mPlainPrompts,
      mContextPromptResolvers: Vector[Any => Prompt.Resolved[F]] = this.mContextPromptResolvers,
      mToolCaps: Option[ToolCapabilities] = this.mToolCaps,
      mResourceCaps: Option[ResourceCapabilities] = this.mResourceCaps,
      mPromptCaps: Option[PromptCapabilities] = this.mPromptCaps,
      mLoggingCaps: Option[LoggingCapabilities] = this.mLoggingCaps
  ): StreamingMcpHttpBuilder[F, Ctx2] =
    new StreamingMcpHttpBuilder[F, Ctx2](
      mName, mVersion, mConfig, mAuthInfo, mStatefulCreators, mAuthExtractor,
      mPlainTools, mContextToolResolvers,
      mPlainResources, mContextResourceResolvers,
      mPlainResourceTemplates, mContextResourceTemplateResolvers,
      mPlainPrompts, mContextPromptResolvers,
      mToolCaps, mResourceCaps, mPromptCaps, mLoggingCaps
    )

  // ── Config ──────────────────────────────────────────────────────────

  def name(n: String): StreamingMcpHttpBuilder[F, Ctx] = copy(mName = n)
  def version(v: String): StreamingMcpHttpBuilder[F, Ctx] = copy(mVersion = v)
  def host(h: Host): StreamingMcpHttpBuilder[F, Ctx] = copy(mConfig = mConfig.copy(host = h))
  def port(p: Port): StreamingMcpHttpBuilder[F, Ctx] = copy(mConfig = mConfig.copy(port = p))
  def withExplorer(redirectToRoot: Boolean = false): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mConfig = mConfig.copy(explorerEnabled = true, rootRedirectToExplorer = redirectToRoot))

  // ── Context accumulation ──────────────────────────────────────────

  def stateful[S](create: NotificationSink[F] => F[S]): StreamingMcpHttpBuilder[F, Append[S, Ctx]] =
    val widened: NotificationSink[F] => F[Any] = sink => create(sink).map(_.asInstanceOf[Any])
    copy[Append[S, Ctx]](mStatefulCreators = mStatefulCreators :+ widened)

  def authenticated[U: Eq](extract: Request[F] => F[Option[U]], onUnauthorized: Response[F]): StreamingMcpHttpBuilder[F, Append[U, Ctx]] =
    val eqAny: Eq[Any] = Eq.instance[Any]((a, b) => summon[Eq[U]].eqv(a.asInstanceOf[U], b.asInstanceOf[U]))
    val widenedExtract: Request[F] => F[Option[Any]] = req => extract(req).map(_.map(_.asInstanceOf[Any]))
    val info = StreamingMcpHttpBuilder.AuthInfo[F](eqAny, widenedExtract, onUnauthorized)
    copy[Append[U, Ctx]](mAuthInfo = Some(info), mAuthExtractor = Some(widenedExtract))

  // ── Plain tools ────────────────────────────────────────────────────

  def withTool(tool: Tool.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainTools = mPlainTools :+ tool)

  def withTools(tools: Tool.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainTools = mPlainTools ++ tools)

  // ── Context tools ──────────────────────────────────────────────────

  def withContextualTool[A, R](tool: Tool[F, Ctx, A, R])(using Async[F]): StreamingMcpHttpBuilder[F, Ctx] =
    withContextualTool(tool, identity)

  def withContextualTool[C, A, R](tool: Tool[F, C, A, R], extract: Ctx => C)(using Async[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mContextToolResolvers = mContextToolResolvers :+ ((ctx: Any) => tool.provide(extract(ctx.asInstanceOf[Ctx]))))

  // ── Plain resources ────────────────────────────────────────────────

  def withResource(handler: McpResource.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResources = mPlainResources :+ handler)

  def withResource(resource: McpResource[F, Unit]): StreamingMcpHttpBuilder[F, Ctx] =
    withResource(resource.resolve)

  def withResources(handlers: McpResource.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResources = mPlainResources ++ handlers)

  // ── Context resources ──────────────────────────────────────────────

  def withContextualResource(resource: McpResource[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mContextResourceResolvers = mContextResourceResolvers :+ ((ctx: Any) => resource.provide(ctx.asInstanceOf[Ctx])))

  // ── Plain resource templates ────────────────────────────────────────

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResourceTemplates = mPlainResourceTemplates :+ handler)

  def withResourceTemplate(rt: ResourceTemplate[F, Unit]): StreamingMcpHttpBuilder[F, Ctx] =
    withResourceTemplate(rt.resolve)

  def withResourceTemplates(handlers: ResourceTemplate.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResourceTemplates = mPlainResourceTemplates ++ handlers)

  // ── Context resource templates ──────────────────────────────────────

  def withContextualResourceTemplate(rt: ResourceTemplate[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mContextResourceTemplateResolvers = mContextResourceTemplateResolvers :+ ((ctx: Any) => rt.provide(ctx.asInstanceOf[Ctx])))

  // ── Plain prompts ──────────────────────────────────────────────────

  def withPrompt(handler: Prompt.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainPrompts = mPlainPrompts :+ handler)

  def withPrompt(prompt: Prompt[F, Unit]): StreamingMcpHttpBuilder[F, Ctx] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainPrompts = mPlainPrompts ++ handlers)

  // ── Context prompts ────────────────────────────────────────────────

  def withContextualPrompt(prompt: Prompt[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mContextPromptResolvers = mContextPromptResolvers :+ ((ctx: Any) => prompt.provide(ctx.asInstanceOf[Ctx])))

  // ── Capabilities ──────────────────────────────────────────────────

  def enableToolNotifications: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mToolCaps = Some(mToolCaps.getOrElse(ToolCapabilities()).copy(listChanged = Some(true))))

  def enableResourceSubscriptions: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mResourceCaps = Some(mResourceCaps.getOrElse(ResourceCapabilities()).copy(subscribe = Some(true))))

  def enableResourceNotifications: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mResourceCaps = Some(mResourceCaps.getOrElse(ResourceCapabilities()).copy(listChanged = Some(true))))

  def enablePromptNotifications: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPromptCaps = Some(mPromptCaps.getOrElse(PromptCapabilities()).copy(listChanged = Some(true))))

  def enableLogging: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mLoggingCaps = Some(LoggingCapabilities()))

  // ── Build session ──────────────────────────────────────────────────

  private def resolveAll(ctx: Any): F[Server[F]] =
    val tools = mPlainTools ++ mContextToolResolvers.map(_(ctx))
    val resources = mPlainResources ++ mContextResourceResolvers.map(_(ctx))
    val resourceTemplates = mPlainResourceTemplates ++ mContextResourceTemplateResolvers.map(_(ctx))
    val prompts = mPlainPrompts ++ mContextPromptResolvers.map(_(ctx))
    val capabilities = ServerCapabilities(
      tools = mToolCaps,
      resources = mResourceCaps,
      prompts = mPromptCaps,
      logging = mLoggingCaps
    )
    DefaultServer[F](
      info = Implementation(mName, mVersion),
      capabilities = capabilities,
      toolHandlers = tools.toList,
      resourceHandlers = resources.toList,
      resourceTemplateHandlers = resourceTemplates.toList,
      promptHandlers = prompts.toList
    ).widen[Server[F]]

  private def createStatefulContext(sink: NotificationSink[F]): F[Any] =
    mStatefulCreators.foldM((): Any) { (ctx, creator) =>
      creator(sink).map { value => StreamingMcpHttpBuilder.prependContext(value, ctx) }
    }

  private[http4s] def newSessionFactory: NotificationSink[F] => F[Server[F]] =
    (sink: NotificationSink[F]) =>
      createStatefulContext(sink).flatMap(resolveAll)

  private[http4s] def newAuthenticatedSessionFactory: (Any, NotificationSink[F]) => F[Server[F]] =
    (user: Any, sink: NotificationSink[F]) =>
      createStatefulContext(sink).map { statefulCtx =>
        StreamingMcpHttpBuilder.prependContext(user, statefulCtx)
      }.flatMap(resolveAll)

  // ── Terminal operations ─────────────────────────────────────────────

  def routes(using UUIDGen[F]): Resource[F, HttpRoutes[F]] =
    mAuthInfo match
      case Some(info) =>
        val extractReq: Request[F] => F[Option[Any]] = mAuthExtractor.getOrElse(_ => Async[F].pure(None))
        StreamableHttpTransport.authenticatedRoutes[F, Any](
          extractReq,
          (user: Any, sink: NotificationSink[F]) => newAuthenticatedSessionFactory(user, sink),
          Async[F].pure(info.onForbidden)
        )(using Async[F], summon[UUIDGen[F]], info.eqAny)
      case None =>
        StreamableHttpTransport.routes[F](newSessionFactory)

object StreamingMcpHttpBuilder:

  private[http4s] final case class AuthInfo[F[_]](
      eqAny: Eq[Any],
      extract: Request[F] => F[Option[Any]],
      onForbidden: Response[F]
  )

  private[http4s] def prependContext(head: Any, tail: Any): Any = tail match
    case () => head
    case _  => (head, tail)

  extension [Ctx](builder: StreamingMcpHttpBuilder[IO, Ctx])
    def serve: Resource[IO, org.http4s.server.Server] =
      given UUIDGen[IO] = UUIDGen.fromSync[IO]
      builder.mAuthInfo match
        case Some(info) =>
          val extractIO: Request[IO] => IO[Option[Any]] =
            builder.mAuthExtractor.getOrElse(_ => IO.pure(None))
          McpHttp.serveStreamableAuthenticated[Any](
            extractIO,
            (user: Any, sink: NotificationSink[IO]) => builder.newAuthenticatedSessionFactory(user, sink),
            info.onForbidden,
            builder.mConfig
          )(using info.eqAny)
        case None =>
          McpHttp.serveStreamable(builder.newSessionFactory, builder.mConfig)
