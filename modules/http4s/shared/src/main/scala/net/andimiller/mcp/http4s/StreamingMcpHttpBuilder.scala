package net.andimiller.mcp.http4s

import cats.Eq
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.UUIDGen
import cats.effect.syntax.all.*
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.state.SessionRefs

import com.comcast.ip4s.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder

/** Per-session slot creator. Yields the typed-erased slot value plus an optional change stream — `None` for read-only
  * `.context[C]` slots, `Some(ref.discrete.void)` for mutable `.state[S]` slots (whose `SignallingRef` powers
  * `tools/list_changed` notifications when visibility predicates depend on them).
  */
private type SlotCreator[F[_]] = SessionContext[F] => F[(Any, Option[Stream[F, Unit]])]

type Append[A, B] = B match
  case Unit => A
  case _    => (A, B)

class StreamingMcpHttpBuilder[F[_]: Async, Ctx] private[http4s] (
    val mName: String,
    val mVersion: String,
    val mConfig: McpHttpConfig,
    val mAuthInfo: Option[StreamingMcpHttpBuilder.AuthInfo[F]],
    val mSlotCreators: Vector[SlotCreator[F]],
    val mAuthExtractor: Option[Request[F] => F[Option[Any]]],
    val mPlainTools: Vector[Tool[F, Ctx]],
    val mContextTools: Vector[Tool[F, Ctx]],
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
    val mSessionStoreFactory: Option[SessionStoreFactory[F]],
    val mTitle: Option[String] = None,
    val mDescription: Option[String] = None,
    val mIcons: List[Icon] = Nil,
    val mWebsiteUrl: Option[String] = None,
    val mExtraRoutes: HttpRoutes[F],
    val mToolMiddlewares: List[ToolMiddleware[F, Ctx]] = Nil
):

  private def copy[Ctx2](
      mName: String = this.mName,
      mVersion: String = this.mVersion,
      mConfig: McpHttpConfig = this.mConfig,
      mAuthInfo: Option[StreamingMcpHttpBuilder.AuthInfo[F]] = this.mAuthInfo,
      mSlotCreators: Vector[SlotCreator[F]] = this.mSlotCreators,
      mAuthExtractor: Option[Request[F] => F[Option[Any]]] = this.mAuthExtractor,
      mPlainTools: Vector[Tool[F, Ctx2]] = this.mPlainTools.asInstanceOf[Vector[Tool[F, Ctx2]]],
      mContextTools: Vector[Tool[F, Ctx2]] = this.mContextTools.asInstanceOf[Vector[Tool[F, Ctx2]]],
      mPlainResources: Vector[McpResource.Resolved[F]] = this.mPlainResources,
      mContextResourceResolvers: Vector[Any => McpResource.Resolved[F]] = this.mContextResourceResolvers,
      mPlainResourceTemplates: Vector[ResourceTemplate.Resolved[F]] = this.mPlainResourceTemplates,
      mContextResourceTemplateResolvers: Vector[Any => ResourceTemplate.Resolved[F]] =
        this.mContextResourceTemplateResolvers,
      mPlainPrompts: Vector[Prompt.Resolved[F]] = this.mPlainPrompts,
      mContextPromptResolvers: Vector[Any => Prompt.Resolved[F]] = this.mContextPromptResolvers,
      mCaps: CapabilityTracker = this.mCaps,
      mSessionStore: Option[SessionStore[F]] = this.mSessionStore,
      mSinkFactory: Option[String => Resource[F, NotificationSink[F]]] = this.mSinkFactory,
      mSessionRefsFactory: Option[String => SessionRefs[F]] = this.mSessionRefsFactory,
      mSessionStoreFactory: Option[SessionStoreFactory[F]] = this.mSessionStoreFactory,
      mTitle: Option[String] = this.mTitle,
      mDescription: Option[String] = this.mDescription,
      mIcons: List[Icon] = this.mIcons,
      mWebsiteUrl: Option[String] = this.mWebsiteUrl,
      mExtraRoutes: HttpRoutes[F] = this.mExtraRoutes,
      mToolMiddlewares: List[ToolMiddleware[F, Ctx2]] =
        this.mToolMiddlewares.asInstanceOf[List[ToolMiddleware[F, Ctx2]]]
  ): StreamingMcpHttpBuilder[F, Ctx2] =
    new StreamingMcpHttpBuilder[F, Ctx2](
      mName, mVersion, mConfig, mAuthInfo, mSlotCreators, mAuthExtractor, mPlainTools, mContextTools,
      mPlainResources, mContextResourceResolvers, mPlainResourceTemplates, mContextResourceTemplateResolvers,
      mPlainPrompts, mContextPromptResolvers, mCaps, mSessionStore, mSinkFactory, mSessionRefsFactory,
      mSessionStoreFactory, mTitle, mDescription, mIcons, mWebsiteUrl, mExtraRoutes, mToolMiddlewares
    )

  // ── Config ──────────────────────────────────────────────────────────

  def name(n: String): StreamingMcpHttpBuilder[F, Ctx] = copy(mName = n)

  def version(v: String): StreamingMcpHttpBuilder[F, Ctx] = copy(mVersion = v)

  def host(h: Host): StreamingMcpHttpBuilder[F, Ctx] = copy(mConfig = mConfig.copy(host = h))

  def port(p: Port): StreamingMcpHttpBuilder[F, Ctx] = copy(mConfig = mConfig.copy(port = p))

  def withExplorer(redirectToRoot: Boolean = false): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mConfig = mConfig.copy(explorerEnabled = true, rootRedirectToExplorer = redirectToRoot))

  // ── Implementation metadata ────────────────────────────────────────

  def title(t: String): StreamingMcpHttpBuilder[F, Ctx] = copy(mTitle = Some(t))

  def description(d: String): StreamingMcpHttpBuilder[F, Ctx] = copy(mDescription = Some(d))

  def icon(i: Icon): StreamingMcpHttpBuilder[F, Ctx] = copy(mIcons = mIcons :+ i)

  def icons(xs: List[Icon]): StreamingMcpHttpBuilder[F, Ctx] = copy(mIcons = xs)

  def websiteUrl(url: String): StreamingMcpHttpBuilder[F, Ctx] = copy(mWebsiteUrl = Some(url))

  /** Mount additional HTTP routes alongside the MCP routes (e.g. `/icon.svg`, `/favicon.ico`, health checks). Composes
    * with existing extras via `<+>`, so this can be called multiple times.
    */
  def withRoutes(extra: HttpRoutes[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mExtraRoutes = mExtraRoutes <+> extra)

  /** Append a server-wide tool middleware. Composed around every tool call, OUTSIDE any per-tool middleware. */
  def withToolMiddleware(mw: ToolMiddleware[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mToolMiddlewares = mToolMiddlewares :+ mw)

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

  /** Register a read-only per-session value. The creator receives a [[SessionContext]] (id, channel, refs,
    * elicitation). Useful for per-session dependencies that don't change during the session — tracers, DB handles,
    * derived configuration.
    *
    * Multiple `.context` / `.state` calls accumulate in declaration order, prepending each new slot to the front of the
    * tuple-shaped context type via `Append[C, Ctx]`.
    */
  def context[C](create: SessionContext[F] => F[C]): StreamingMcpHttpBuilder[F, Append[C, Ctx]] =
    val widened: SlotCreator[F] = ctx => create(ctx).map(value => (value.asInstanceOf[Any], None))
    copy[Append[C, Ctx]](mSlotCreators = mSlotCreators :+ widened)

  /** Register mutable per-session state. The framework wraps the initial value in a [[fs2.concurrent.SignallingRef]]
    * and exposes that ref to handlers as the corresponding Ctx slot — handlers call `.get` / `.update` / `.modify` on
    * it directly.
    *
    * When tool visibility predicates depend on `.state` slots, the framework subscribes to the ref's `discrete` stream
    * per session, recomputes the visible tool set on every change, and emits `notifications/tools/list_changed` when
    * the set differs. The `tools/listChanged` server capability is enabled automatically.
    */
  def state[S](initial: SessionContext[F] => F[S]): StreamingMcpHttpBuilder[F, Append[SignallingRef[F, S], Ctx]] =
    val widened: SlotCreator[F] = ctx =>
      initial(ctx).flatMap(s => SignallingRef.of[F, Any](s).map(ref => (ref.asInstanceOf[Any], Some(ref.discrete.void))))
    copy[Append[SignallingRef[F, S], Ctx]](
      mSlotCreators = mSlotCreators :+ widened,
      mCaps = mCaps.withToolNotifications
    )

  def authenticated[U: Eq](
      extract: Request[F] => F[Option[U]],
      onUnauthorized: Response[F]
  ): StreamingMcpHttpBuilder[F, Append[U, Ctx]] =
    val eqAny: Eq[Any]                               = Eq.instance[Any]((a, b) => summon[Eq[U]].eqv(a.asInstanceOf[U], b.asInstanceOf[U]))
    val widenedExtract: Request[F] => F[Option[Any]] = req => extract(req).map(_.map(_.asInstanceOf[Any]))
    val info                                         = StreamingMcpHttpBuilder.AuthInfo[F](eqAny, widenedExtract, onUnauthorized)
    copy[Append[U, Ctx]](mAuthInfo = Some(info), mAuthExtractor = Some(widenedExtract))

  // ── Plain tools ────────────────────────────────────────────────────

  def withTool(tool: Tool[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainTools = mPlainTools :+ tool, mCaps = mCaps.withToolAdded)

  def withTools(tools: Tool[F, Ctx]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainTools = mPlainTools ++ tools, mCaps = if tools.nonEmpty then mCaps.withToolAdded else mCaps)

  // ── Context tools ──────────────────────────────────────────────────

  /** Register a tool that wants to read the per-session `Ctx` inside its handler. The tool's `handle` method receives
    * the same `Ctx` value that's threaded into [[ToolCallContext]] for middleware, so per-tool middleware on a
    * contextual tool sees the matching session state.
    */
  def withContextualTool(tool: Tool[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mContextTools = mContextTools :+ tool, mCaps = mCaps.withToolAdded)

  // ── Plain resources ────────────────────────────────────────────────

  def withResource(handler: McpResource.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResources = mPlainResources :+ handler, mCaps = mCaps.withResourceAdded)

  def withResource(resource: McpResource[F, Unit]): StreamingMcpHttpBuilder[F, Ctx] =
    withResource(resource.resolve)

  def withResources(handlers: McpResource.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(
      mPlainResources = mPlainResources ++ handlers,
      mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps
    )

  // ── Context resources ──────────────────────────────────────────────

  def withContextualResource(resource: McpResource[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(
      mContextResourceResolvers = mContextResourceResolvers :+ ((ctx: Any) => resource.provide(ctx.asInstanceOf[Ctx])),
      mCaps = mCaps.withResourceAdded
    )

  // ── Plain resource templates ────────────────────────────────────────

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainResourceTemplates = mPlainResourceTemplates :+ handler, mCaps = mCaps.withResourceAdded)

  def withResourceTemplate(rt: ResourceTemplate[F, Unit]): StreamingMcpHttpBuilder[F, Ctx] =
    withResourceTemplate(rt.resolve)

  def withResourceTemplates(handlers: ResourceTemplate.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(
      mPlainResourceTemplates = mPlainResourceTemplates ++ handlers,
      mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps
    )

  // ── Context resource templates ──────────────────────────────────────

  def withContextualResourceTemplate(rt: ResourceTemplate[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(
      mContextResourceTemplateResolvers =
        mContextResourceTemplateResolvers :+ ((ctx: Any) => rt.provide(ctx.asInstanceOf[Ctx])),
      mCaps = mCaps.withResourceAdded
    )

  // ── Plain prompts ──────────────────────────────────────────────────

  def withPrompt(handler: Prompt.Resolved[F]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainPrompts = mPlainPrompts :+ handler, mCaps = mCaps.withPromptAdded)

  def withPrompt(prompt: Prompt[F, Unit]): StreamingMcpHttpBuilder[F, Ctx] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): StreamingMcpHttpBuilder[F, Ctx] =
    copy(mPlainPrompts = mPlainPrompts ++ handlers, mCaps = if handlers.nonEmpty then mCaps.withPromptAdded else mCaps)

  // ── Context prompts ────────────────────────────────────────────────

  def withContextualPrompt(prompt: Prompt[F, Ctx]): StreamingMcpHttpBuilder[F, Ctx] =
    copy(
      mContextPromptResolvers = mContextPromptResolvers :+ ((ctx: Any) => prompt.provide(ctx.asInstanceOf[Ctx])),
      mCaps = mCaps.withPromptAdded
    )

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

  /** Build a standalone Server[F] using a no-op client channel and in-memory session refs. Useful for golden testing
    * where only catalog queries (listTools, etc.) are needed — tools that issue server-initiated requests will get
    * [[ServerRequester]]'s "not configured" response if exercised against this server. The visibility-watcher fiber is
    * not spawned (no real client to notify); visibility predicates still run on each `listTools` / `callTool` call.
    */
  def buildServer: F[Server[F]] = buildServerWith(_ => Async[F].unit)

  /** Like [[buildServer]] but lets the caller seed the per-session Ctx before the server is exposed. Use it in golden
    * tests to snapshot multiple visibility scenarios — e.g. set a `SignallingRef` to a non-empty value so dynamic tools
    * gated by its content appear in the captured `tools/list`.
    */
  def buildServerWith(prep: Ctx => F[Unit]): F[Server[F]] =
    ClientChannel.noop[F].flatMap { cc =>
      val ctx = SessionContext("noop", cc, SessionRefs.inMemory[F])
      buildSlots(ctx).flatMap { case (statefulCtx, _) =>
        prep(statefulCtx.asInstanceOf[Ctx]) >> resolveAll(statefulCtx, None)
      }
    }

  private def resolveAll(ctx: Any, sessionId: Option[String]): F[Server[F]] =
    val tools             = mPlainTools ++ mContextTools
    val resources         = mPlainResources ++ mContextResourceResolvers.map(_(ctx))
    val resourceTemplates = mPlainResourceTemplates ++ mContextResourceTemplateResolvers.map(_(ctx))
    val prompts           = mPlainPrompts ++ mContextPromptResolvers.map(_(ctx))
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
      ctx = ctx.asInstanceOf[Ctx],
      toolHandlers = tools.toList,
      resourceHandlers = resources.toList,
      resourceTemplateHandlers = resourceTemplates.toList,
      promptHandlers = prompts.toList,
      toolMiddlewares = mToolMiddlewares,
      sessionId = sessionId
    ).widen[Server[F]]

  /** Run all registered slot creators against a single [[SessionContext]] in declaration order, prepending each result
    * onto the accumulated context tuple. Also surfaces the discrete change streams for `.state` slots so the
    * visibility-watcher fiber can subscribe to them.
    */
  private def buildSlots(ctx: SessionContext[F]): F[(Any, List[Stream[F, Unit]])] =
    mSlotCreators.foldLeft(Async[F].pure(((), List.empty[Stream[F, Unit]]): (Any, List[Stream[F, Unit]]))) {
      (accF, creator) =>
        accF.flatMap { case (ctxAcc, streamsAcc) =>
          creator(ctx).map { case (value, streamOpt) =>
            val newCtx     = StreamingMcpHttpBuilder.prependContext(value, ctxAcc)
            val newStreams = streamOpt.fold(streamsAcc)(_ :: streamsAcc)
            (newCtx, newStreams)
          }
        }
    }

  /** Compute the set of currently-visible tool names against the supplied (already-built) Ctx. Used by the
    * visibility-watcher fiber to decide whether to emit `notifications/tools/list_changed`.
    */
  private def computeVisibleNames(ctx: Any): F[Set[String]] =
    val all = (mPlainTools ++ mContextTools).toList
    all
      .filterA { t =>
        t.visible.fold(Async[F].pure(true))(p => p.asInstanceOf[Any => F[Boolean]](ctx))
      }
      .map(_.iterator.map(_.name).toSet)

  /** Spawn the per-session visibility-watcher fiber. Returns the cleanup action to cancel it. No-op if no `.state`
    * slots were registered (and hence no change streams to subscribe to).
    */
  private def spawnVisibilityWatcher(
      ctx: Any,
      sink: NotificationSink[F],
      streams: List[Stream[F, Unit]]
  ): F[F[Unit]] =
    if streams.isEmpty then Async[F].pure(Async[F].unit)
    else
      val merged = streams.reduceLeft(_.merge(_))
      for
        initial <- computeVisibleNames(ctx)
        lastSet <- Ref.of[F, Set[String]](initial)
        fiber   <- merged
                     .evalMap { _ =>
                       computeVisibleNames(ctx).flatMap { current =>
                         lastSet.getAndSet(current).flatMap { previous =>
                           if previous != current then sink.toolListChanged else Async[F].unit
                         }
                       }
                     }
                     .compile
                     .drain
                     .start
      yield fiber.cancel

  private[http4s] def newSessionFactory(
      sessionId: String
  ): SessionContext[F] => F[(Server[F], F[Unit])] =
    ctx =>
      buildSlots(ctx).flatMap { case (statefulCtx, changeStreams) =>
        for
          server  <- resolveAll(statefulCtx, Some(sessionId))
          cleanup <- spawnVisibilityWatcher(statefulCtx, ctx.channel.sink, changeStreams)
        yield (server, cleanup)
      }

  private[http4s] def newAuthenticatedSessionFactory(
      sessionId: String
  ): (Any, SessionContext[F]) => F[(Server[F], F[Unit])] =
    (user, ctx) =>
      buildSlots(ctx).flatMap { case (statefulCtx, changeStreams) =>
        val fullCtx = StreamingMcpHttpBuilder.prependContext(user, statefulCtx)
        for
          server  <- resolveAll(fullCtx, Some(sessionId))
          cleanup <- spawnVisibilityWatcher(fullCtx, ctx.channel.sink, changeStreams)
        yield (server, cleanup)
      }

  // ── Terminal operations ─────────────────────────────────────────────

  private def sinkFactory: String => Resource[F, NotificationSink[F]] =
    mSinkFactory.getOrElse(_ => NotificationSink.create[F])

  private def refsFactory: String => SessionRefs[F] =
    mSessionRefsFactory.getOrElse(_ => SessionRefs.inMemory[F])

  def routes(using UUIDGen[F]): Resource[F, HttpRoutes[F]] =
    mAuthInfo match
      case Some(info) =>
        val extractReq: Request[F] => F[Option[Any]] = mAuthExtractor.getOrElse(_ => Async[F].pure(None))
        val storeR                                   = mSessionStore match
          case Some(store: AuthenticatedSessionStore[F, Any] @unchecked) =>
            Resource.pure[F, AuthenticatedSessionStore[F, Any]](store)
          case _ => Resource.eval(AuthenticatedSessionStore.inMemory[F, Any])
        storeR.flatMap { store =>
          val serverF: (String, Any, SessionContext[F]) => F[StreamableHttpTransport.ServerFactoryOutput[F]] =
            (id, user, ctx) => newAuthenticatedSessionFactory(id)(user, ctx)
          StreamableHttpTransport.authenticatedRoutes[F, Any](
            extractReq,
            serverF,
            Async[F].pure(info.onForbidden),
            sinkFactory,
            refsFactory,
            store
          )(using Async[F], summon[UUIDGen[F]], info.eqAny)
        }
      case None =>
        val storeR: Resource[F, SessionStore[F]] = mSessionStoreFactory match
          case Some(factory) =>
            val reconstruct: String => F[McpSession[F]] = (id: String) =>
              for
                sinkPair             <- sinkFactory(id).allocated
                (sink, _)             = sinkPair
                ccPair               <- ClientChannel.fromSink[F](sink).allocated
                (cc, _)               = ccPair
                ctx                   = SessionContext(id, cc, refsFactory(id))
                factoryOut           <- newSessionFactory(id)(ctx)
                (server, cleanup)     = factoryOut
                handler               = new RequestHandler[F](server, cc.requester, cc.cancellation)
                subscriptions        <- Ref.of[F, Set[String]](Set.empty)
              yield McpSession(id, handler, cc, subscriptions, cleanup)
            Resource.eval(factory.create(reconstruct))
          case None =>
            mSessionStore.fold(Resource.eval(SessionStore.inMemory[F]))(Resource.pure(_))
        storeR.flatMap { store =>
          val serverF: (String, SessionContext[F]) => F[StreamableHttpTransport.ServerFactoryOutput[F]] =
            (id, ctx) => newSessionFactory(id)(ctx)
          StreamableHttpTransport.routes(serverF, sinkFactory, refsFactory, store)
        }

object StreamingMcpHttpBuilder:

  final private[http4s] case class AuthInfo[F[_]](
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
        val combined = mcpRoutes <+> builder.mExtraRoutes
        val app      = McpHttp.buildApp(combined, builder.mConfig)
        EmberServerBuilder
          .default[IO]
          .withHost(builder.mConfig.host)
          .withPort(builder.mConfig.port)
          .withHttpApp(app)
          .build
      }
