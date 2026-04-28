package net.andimiller.mcp.http4s

import cats.Eq
import cats.effect.IO
import cats.effect.kernel.{Async, Ref, Resource}
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import com.comcast.ip4s.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.state.SessionRefs
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder

type Append[A, B] = B match
  case Unit => A
  case _    => (A, B)

class StreamingMcpHttpBuilder[F[_]: Async, Ctx] private[http4s] (
    val mName: String,
    val mVersion: String,
    val mConfig: McpHttpConfig,
    val mAuthInfo: Option[StreamingMcpHttpBuilder.AuthInfo[F]],
    val mStatefulCreators: Vector[SessionContext[F] => F[Any]],
    val mAuthExtractor: Option[Request[F] => F[Option[Any]]],
    val mPlainTools: Vector[Tool.Resolved[F]],
    val mContextToolResolvers: Vector[Any => Tool.Resolved[F]],
    val mPlainResources: Vector[McpResource.Resolved[F]],
    val mContextResourceResolvers: Vector[Any => McpResource.Resolved[F]],
    val mPlainResourceTemplates: Vector[ResourceTemplate.Resolved[F]],
    val mContextResourceTemplateResolvers: Vector[Any => ResourceTemplate.Resolved[F]],
    val mPlainPrompts: Vector[Prompt.Resolved[F]],
    val mContextPromptResolvers: Vector[Any => Prompt.Resolved[F]],
    val mCaps: CapabilityTracker,
    val mSessionStore: Option[SessionStore[F]],
    val mSinkFactory: Option[String => Resource[F, NotificationSink[F]]],
    val mSessionRefsFactory: Option[String => SessionRefs[F]],
    val mSessionStoreFactory: Option[SessionStoreFactory[F]]
):

  private def copy[Ctx2](
      mName: String = this.mName,
      mVersion: String = this.mVersion,
      mConfig: McpHttpConfig = this.mConfig,
      mAuthInfo: Option[StreamingMcpHttpBuilder.AuthInfo[F]] = this.mAuthInfo,
      mStatefulCreators: Vector[SessionContext[F] => F[Any]] = this.mStatefulCreators,
      mAuthExtractor: Option[Request[F] => F[Option[Any]]] = this.mAuthExtractor,
      mPlainTools: Vector[Tool.Resolved[F]] = this.mPlainTools,
      mContextToolResolvers: Vector[Any => Tool.Resolved[F]] = this.mContextToolResolvers,
      mPlainResources: Vector[McpResource.Resolved[F]] = this.mPlainResources,
      mContextResourceResolvers: Vector[Any => McpResource.Resolved[F]] = this.mContextResourceResolvers,
      mPlainResourceTemplates: Vector[ResourceTemplate.Resolved[F]] = this.mPlainResourceTemplates,
      mContextResourceTemplateResolvers: Vector[Any => ResourceTemplate.Resolved[F]] = this.mContextResourceTemplateResolvers,
      mPlainPrompts: Vector[Prompt.Resolved[F]] = this.mPlainPrompts,
      mContextPromptResolvers: Vector[Any => Prompt.Resolved[F]] = this.mContextPromptResolvers,
      mCaps: CapabilityTracker = this.mCaps,
      mSessionStore: Option[SessionStore[F]] = this.mSessionStore,
      mSinkFactory: Option[String => Resource[F, NotificationSink[F]]] = this.mSinkFactory,
      mSessionRefsFactory: Option[String => SessionRefs[F]] = this.mSessionRefsFactory,
      mSessionStoreFactory: Option[SessionStoreFactory[F]] = this.mSessionStoreFactory
  ): StreamingMcpHttpBuilder[F, Ctx2] =
    new StreamingMcpHttpBuilder[F, Ctx2](
      mName, mVersion, mConfig, mAuthInfo,
      mStatefulCreators,
      mAuthExtractor,
      mPlainTools, mContextToolResolvers,
      mPlainResources, mContextResourceResolvers,
      mPlainResourceTemplates, mContextResourceTemplateResolvers,
      mPlainPrompts, mContextPromptResolvers,
      mCaps,
      mSessionStore, mSinkFactory, mSessionRefsFactory, mSessionStoreFactory
    )

  // ── Config ──────────────────────────────────────────────────────────

  def name(n: String): StreamingMcpHttpBuilder[F, Ctx] = copy(mName = n)
  def version(v: String): StreamingMcpHttpBuilder[F, Ctx] = copy(mVersion = v)
  def host(h: Host): StreamingMcpHttpBuilder[F, Ctx] = copy(mConfig = mConfig.copy(host = h))
  def port(p: Port): StreamingMcpHttpBuilder[F, Ctx] = copy(mConfig = mConfig.copy(port = p))
  def withExplorer(redirectToRoot: Boolean = false): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mConfig = mConfig.copy(explorerEnabled = true, rootRedirectToExplorer = redirectToRoot))

  // ── Session store / factory configuration ──────────────────────────

  def withSessionStore(store: SessionStore[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mSessionStore = Some(store))

  def withNotificationSinkFactory(f: String => Resource[F, NotificationSink[F]]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mSinkFactory = Some(f))

  def withSessionRefsFactory(f: String => SessionRefs[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mSessionRefsFactory = Some(f))

  def withSessionStoreFactory(factory: SessionStoreFactory[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mSessionStoreFactory = Some(factory))

  // ── Context accumulation ──────────────────────────────────────────

  /**
   * Register a per-session state creator. The creator receives a [[SessionContext]] —
   * a bundle of `id`, `channel` (notifications + server-initiated requests), and `refs`
   * (per-session named refs). Use the conveniences `ctx.sink` and `ctx.requester` for the
   * common cases, or reach for `ctx.channel` directly to grab the full bidirectional channel.
   *
   * Multiple `.stateful` calls accumulate in declaration order and produce a tuple-shaped
   * context type via `Append[S, Ctx]`.
   */
  def stateful[S](create: SessionContext[F] => F[S]): StreamingMcpHttpBuilder[F, Append[S, Ctx]] =
    val widened: SessionContext[F] => F[Any] = ctx => create(ctx).map(_.asInstanceOf[Any])
    copy[Append[S, Ctx]](mStatefulCreators = mStatefulCreators :+ widened)

  def authenticated[U: Eq](extract: Request[F] => F[Option[U]], onUnauthorized: Response[F]): StreamingMcpHttpBuilder[F, Append[U, Ctx]] =
    val eqAny: Eq[Any] = Eq.instance[Any]((a, b) => summon[Eq[U]].eqv(a.asInstanceOf[U], b.asInstanceOf[U]))
    val widenedExtract: Request[F] => F[Option[Any]] = req => extract(req).map(_.map(_.asInstanceOf[Any]))
    val info = StreamingMcpHttpBuilder.AuthInfo[F](eqAny, widenedExtract, onUnauthorized)
    copy[Append[U, Ctx]](mAuthInfo = Some(info), mAuthExtractor = Some(widenedExtract))

  // ── Plain tools ────────────────────────────────────────────────────

  def withTool(tool: Tool.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainTools = mPlainTools :+ tool, mCaps = mCaps.withToolAdded)

  def withTools(tools: Tool.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainTools = mPlainTools ++ tools, mCaps = if tools.nonEmpty then mCaps.withToolAdded else mCaps)

  // ── Context tools ──────────────────────────────────────────────────

  def withContextualTool[A, R](tool: Tool[F, Ctx, A, R])(using Async[F]): StreamingMcpHttpBuilder[F, Ctx] =
    withContextualTool(tool, identity)

  def withContextualTool[C, A, R](tool: Tool[F, C, A, R], extract: Ctx => C)(using Async[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mContextToolResolvers = mContextToolResolvers :+ ((ctx: Any) => tool.provide(extract(ctx.asInstanceOf[Ctx]))), mCaps = mCaps.withToolAdded)

  // ── Plain resources ────────────────────────────────────────────────

  def withResource(handler: McpResource.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResources = mPlainResources :+ handler, mCaps = mCaps.withResourceAdded)

  def withResource(resource: McpResource[F, Unit]): StreamingMcpHttpBuilder[F, Ctx] =
    withResource(resource.resolve)

  def withResources(handlers: McpResource.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResources = mPlainResources ++ handlers, mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps)

  // ── Context resources ──────────────────────────────────────────────

  def withContextualResource(resource: McpResource[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mContextResourceResolvers = mContextResourceResolvers :+ ((ctx: Any) => resource.provide(ctx.asInstanceOf[Ctx])), mCaps = mCaps.withResourceAdded)

  // ── Plain resource templates ────────────────────────────────────────

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResourceTemplates = mPlainResourceTemplates :+ handler, mCaps = mCaps.withResourceAdded)

  def withResourceTemplate(rt: ResourceTemplate[F, Unit]): StreamingMcpHttpBuilder[F, Ctx] =
    withResourceTemplate(rt.resolve)

  def withResourceTemplates(handlers: ResourceTemplate.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResourceTemplates = mPlainResourceTemplates ++ handlers, mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps)

  // ── Context resource templates ──────────────────────────────────────

  def withContextualResourceTemplate(rt: ResourceTemplate[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mContextResourceTemplateResolvers = mContextResourceTemplateResolvers :+ ((ctx: Any) => rt.provide(ctx.asInstanceOf[Ctx])), mCaps = mCaps.withResourceAdded)

  // ── Plain prompts ──────────────────────────────────────────────────

  def withPrompt(handler: Prompt.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainPrompts = mPlainPrompts :+ handler, mCaps = mCaps.withPromptAdded)

  def withPrompt(prompt: Prompt[F, Unit]): StreamingMcpHttpBuilder[F, Ctx] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainPrompts = mPlainPrompts ++ handlers, mCaps = if handlers.nonEmpty then mCaps.withPromptAdded else mCaps)

  // ── Context prompts ────────────────────────────────────────────────

  def withContextualPrompt(prompt: Prompt[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mContextPromptResolvers = mContextPromptResolvers :+ ((ctx: Any) => prompt.provide(ctx.asInstanceOf[Ctx])), mCaps = mCaps.withPromptAdded)

  // ── Capabilities ──────────────────────────────────────────────────

  def enableToolNotifications: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withToolNotifications)

  def enableResourceSubscriptions: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withResourceSubscriptions)

  def enableResourceNotifications: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withResourceNotifications)

  def enablePromptNotifications: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withPromptNotifications)

  def enableLogging: StreamingMcpHttpBuilder[F, Ctx] =
    copy(mCaps = mCaps.withLogging)

  // ── Build session ──────────────────────────────────────────────────

  /** Build a standalone Server[F] using a no-op client channel and in-memory session refs.
    * Useful for golden testing where only catalog queries (listTools, etc.) are needed —
    * tools that issue server-initiated requests will get [[ServerRequester]]'s "not
    * configured" response if exercised against this server.
    */
  def buildServer: F[Server[F]] =
    ClientChannel.noop[F].flatMap { cc =>
      val ctx = SessionContext("noop", cc, SessionRefs.inMemory[F])
      createStatefulContext(ctx).flatMap(resolveAll)
    }

  private def resolveAll(ctx: Any): F[Server[F]] =
    val tools = mPlainTools ++ mContextToolResolvers.map(_(ctx))
    val resources = mPlainResources ++ mContextResourceResolvers.map(_(ctx))
    val resourceTemplates = mPlainResourceTemplates ++ mContextResourceTemplateResolvers.map(_(ctx))
    val prompts = mPlainPrompts ++ mContextPromptResolvers.map(_(ctx))
    DefaultServer[F](
      info = Implementation(mName, mVersion),
      capabilities = mCaps.toServerCapabilities,
      toolHandlers = tools.toList,
      resourceHandlers = resources.toList,
      resourceTemplateHandlers = resourceTemplates.toList,
      promptHandlers = prompts.toList
    ).widen[Server[F]]

  /** Run all registered `.stateful` creators against a single [[SessionContext]] in
   *  declaration order, prepending each result onto the accumulated context tuple. */
  private def createStatefulContext(ctx: SessionContext[F]): F[Any] =
    mStatefulCreators.foldLeft(Async[F].pure(()): F[Any]) { (ctxF, creator) =>
      ctxF.flatMap(acc => creator(ctx).map(value => StreamingMcpHttpBuilder.prependContext(value, acc)))
    }

  private[http4s] def newSessionFactory(sessionId: String): SessionContext[F] => F[Server[F]] =
    ctx => createStatefulContext(ctx).flatMap(resolveAll)

  private[http4s] def newAuthenticatedSessionFactory(sessionId: String): (Any, SessionContext[F]) => F[Server[F]] =
    (user, ctx) =>
      createStatefulContext(ctx)
        .map(statefulCtx => StreamingMcpHttpBuilder.prependContext(user, statefulCtx))
        .flatMap(resolveAll)

  // ── Terminal operations ─────────────────────────────────────────────

  private def sinkFactory: String => Resource[F, NotificationSink[F]] =
    mSinkFactory.getOrElse(_ => NotificationSink.create[F])

  private def refsFactory: String => SessionRefs[F] =
    mSessionRefsFactory.getOrElse(_ => SessionRefs.inMemory[F])

  def routes(using UUIDGen[F]): Resource[F, HttpRoutes[F]] =
    mAuthInfo match
      case Some(info) =>
        val extractReq: Request[F] => F[Option[Any]] = mAuthExtractor.getOrElse(_ => Async[F].pure(None))
        val storeR = mSessionStore match
          case Some(store: AuthenticatedSessionStore[F, Any] @unchecked) => Resource.pure[F, AuthenticatedSessionStore[F, Any]](store)
          case _ => Resource.eval(AuthenticatedSessionStore.inMemory[F, Any])
        storeR.flatMap { store =>
          val serverF: (String, Any, SessionContext[F]) => F[Server[F]] =
            (id, user, ctx) => newAuthenticatedSessionFactory(id)(user, ctx)
          StreamableHttpTransport.authenticatedRoutes[F, Any](
            extractReq, serverF, Async[F].pure(info.onForbidden), sinkFactory, refsFactory, store
          )(using Async[F], summon[UUIDGen[F]], info.eqAny)
        }
      case None =>
        val storeR: Resource[F, SessionStore[F]] = mSessionStoreFactory match
          case Some(factory) =>
            val reconstruct: String => F[McpSession[F]] = (id: String) =>
              for
                sinkPair      <- sinkFactory(id).allocated
                (sink, _)      = sinkPair
                ccPair        <- ClientChannel.fromSink[F](sink).allocated
                (cc, _)        = ccPair
                ctx            = SessionContext(id, cc, refsFactory(id))
                server        <- newSessionFactory(id)(ctx)
                handler        = new RequestHandler[F](server, cc.requester, cc.cancellation)
                subscriptions <- Ref.of[F, Set[String]](Set.empty)
              yield McpSession(id, handler, cc, subscriptions)
            Resource.eval(factory.create(reconstruct))
          case None =>
            mSessionStore.fold(Resource.eval(SessionStore.inMemory[F]))(Resource.pure(_))
        storeR.flatMap { store =>
          val serverF: (String, SessionContext[F]) => F[Server[F]] =
            (id, ctx) => newSessionFactory(id)(ctx)
          StreamableHttpTransport.routes(serverF, sinkFactory, refsFactory, store)
        }

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
      builder.routes.flatMap { mcpRoutes =>
        val app = McpHttp.buildApp(mcpRoutes, builder.mConfig)
        EmberServerBuilder.default[IO]
          .withHost(builder.mConfig.host)
          .withPort(builder.mConfig.port)
          .withHttpApp(app)
          .build
      }
