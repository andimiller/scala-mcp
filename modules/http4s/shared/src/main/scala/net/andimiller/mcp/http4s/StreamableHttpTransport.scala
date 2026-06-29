package net.andimiller.mcp.http4s

import cats.Eq
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.UUIDGen
import cats.syntax.all.*

import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.server.ClientChannel
import net.andimiller.mcp.core.server.NotificationSink
import net.andimiller.mcp.core.server.RequestHandler
import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.core.server.SessionContext
import net.andimiller.mcp.core.state.SessionRefs

import fs2.Stream
import io.circe.Encoder
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*
import org.typelevel.log4cats.LoggerFactory

/** Streamable HTTP transport for MCP (spec 2025-03-26).
  *
  * Provides three endpoints on a single path:
  *   - '''POST /mcp''' — send JSON-RPC requests/notifications
  *   - '''GET /mcp''' — open an SSE stream for server-initiated notifications
  *   - '''DELETE /mcp''' — terminate a session
  *
  * Sessions are created on `initialize` and identified by the `Mcp-Session-Id` header. The `serverFactory` receives a
  * [[SessionContext]] containing the session id, the bidirectional [[ClientChannel]], and a per-session
  * [[SessionRefs]].
  */
object StreamableHttpTransport:

  private val mcpSessionId = ci"Mcp-Session-Id"

  private val eventStreamMediaType = new MediaType("text", "event-stream")

  /** Convenience: build routes with default in-memory sinks, refs, and session store. */
  def routes[F[_]: Async: UUIDGen: LoggerFactory](
      serverFactory: (String, SessionContext[F]) => F[Server[F]]
  ): Resource[F, HttpRoutes[F]] =
    Resource.eval(SessionStore.inMemory[F]).map { store =>
      unauthenticatedRoutes(serverFactory, defaultSinkFactory[F], defaultRefsFactory[F], store)
    }

  /** Build routes with user-supplied per-session factories.
    *
    * @param serverFactory
    *   receives the new session's id and [[SessionContext]] and produces a `Server[F]`
    * @param sinkFactory
    *   builds a [[NotificationSink]] per session id (default: in-memory)
    * @param refsFactory
    *   builds a [[SessionRefs]] per session id (default: in-memory)
    * @param store
    *   where to persist [[McpSession]]s by id
    */
  def routes[F[_]: Async: UUIDGen: LoggerFactory](
      serverFactory: (String, SessionContext[F]) => F[Server[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: SessionStore[F]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(unauthenticatedRoutes(serverFactory, sinkFactory, refsFactory, store))

  /** Build authenticated HTTP routes. On `initialize`, the extracted user identity is stored alongside the session;
    * subsequent requests must present credentials for the same user (enforced via `Eq[U]`).
    */
  def authenticatedRoutes[F[_]: Async: UUIDGen: LoggerFactory, U: Eq: Encoder](
      authenticate: Request[F] => F[Option[U]],
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      onUnauthorized: F[Response[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: AuthenticatedSessionStore[F, U]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(authedRoutes(authenticate, serverFactory, onUnauthorized, sinkFactory, refsFactory, store))

  /** Convenience: authenticated routes with default in-memory factories and store. */
  def authenticatedRoutes[F[_]: Async: UUIDGen: LoggerFactory, U: Eq: Encoder](
      authenticate: Request[F] => F[Option[U]],
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      onUnauthorized: F[Response[F]]
  ): Resource[F, HttpRoutes[F]] =
    Resource.eval(AuthenticatedSessionStore.inMemory[F, U]).map { store =>
      authedRoutes(authenticate, serverFactory, onUnauthorized, defaultSinkFactory[F], defaultRefsFactory[F], store)
    }

  // ── Defaults ───────────────────────────────────────────────────────

  private def defaultSinkFactory[F[_]: Async]: String => Resource[F, NotificationSink[F]] =
    _ => NotificationSink.create[F]

  private def defaultRefsFactory[F[_]: Async]: String => SessionRefs[F] =
    _ => SessionRefs.inMemory[F]

  // ── Helpers ────────────────────────────────────────────────────────

  private def getSessionId[F[_]](req: Request[F]): Option[String] =
    req.headers.get(mcpSessionId).map(_.head.value)

  private def readBody[F[_]: Async](req: Request[F]): F[Either[String, Message]] =
    req.bodyText.compile.string.map { body =>
      decode[Message](body).leftMap(err => s"Invalid JSON-RPC message: ${err.getMessage}")
    }

  /** Build a session given a SessionContext-aware factory. */
  private def createSessionFromContext[F[_]: Async: UUIDGen: LoggerFactory](
      serverFactory: (String, SessionContext[F]) => F[Server[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: SessionStore[F]
  ): F[McpSession[F]] =
    val logger = LoggerFactory[F].getLoggerFromName("net.andimiller.mcp.http4s.StreamableHttpTransport")
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory(id).allocated
      (sink, _)      = sinkPair
      ccPair        <- ClientChannel.fromSink[F](sink).allocated
      (cc, _)        = ccPair
      ctx            = SessionContext[F](id, cc, refsFactory(id))
      server        <- serverFactory(id, ctx)
      handler        = new RequestHandler[F](id, server, cc.requester, cc.cancellation)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, cc, subscriptions)
      _             <- store.put(session)
      _             <- logger.info(Map("sessionId" -> id, "transport" -> "http"))("session created")
    yield session

  /** Same as [[createSessionFromContext]] but additionally binds the user identity in the authenticated session store. */
  private def createAuthenticatedSession[F[_]: Async: UUIDGen: LoggerFactory, U: Encoder](
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: AuthenticatedSessionStore[F, U],
      user: U
  ): F[McpSession[F]] =
    val logger = LoggerFactory[F].getLoggerFromName("net.andimiller.mcp.http4s.StreamableHttpTransport")
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory(id).allocated
      (sink, _)      = sinkPair
      ccPair        <- ClientChannel.fromSink[F](sink).allocated
      (cc, _)        = ccPair
      ctx            = SessionContext[F](id, cc, refsFactory(id))
      server        <- serverFactory(id, user, ctx)
      handler        = new RequestHandler[F](id, server, cc.requester, cc.cancellation)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, cc, subscriptions)
      _             <- store.put(session)
      _             <- store.putUser(id, user)
      _             <- logger.info(
             Map("sessionId" -> id, "user" -> encodeUserForLog(user), "transport" -> "http-auth")
           )("session created")
    yield session

  /** Truncate the JSON-encoded form of `user` to 256 chars so logs don't blow up on pathological encoders. */
  private[http4s] def encodeUserForLog[U: Encoder](u: U): String =
    val s = u.asJson.noSpaces
    if s.length > 256 then s.take(256) + "…(truncated)" else s

  // ── Routes assembly ────────────────────────────────────────────────

  /** Thin wrapper for the unauthenticated path — supplies no-op auth/validation hooks. */
  private def unauthenticatedRoutes[F[_]: Async: UUIDGen: LoggerFactory](
      serverFactory: (String, SessionContext[F]) => F[Server[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: SessionStore[F]
  ): HttpRoutes[F] =
    buildRoutes[F, Unit](
      authCheck = _ => Async[F].pure(Right(())),
      initSession = _ => createSessionFromContext(serverFactory, sinkFactory, refsFactory, store),
      validateSession = (_, _) => Async[F].pure(Right(())),
      store = store
    )

  /** Thin wrapper for the authenticated path — wires `authenticate`, `getUser`-based validation, and per-user session
    * binding into the unified route builder.
    */
  private def authedRoutes[F[_]: Async: UUIDGen: LoggerFactory, U: Eq: Encoder](
      authenticate: Request[F] => F[Option[U]],
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      onUnauthorized: F[Response[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: AuthenticatedSessionStore[F, U]
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*
    val logger = LoggerFactory[F].getLoggerFromName("net.andimiller.mcp.http4s.StreamableHttpTransport")

    buildRoutes[F, U](
      authCheck = req =>
        authenticate(req).flatMap {
          case Some(user) => Async[F].pure(Right(user))
          case None       => onUnauthorized.map(Left(_))
        },
      initSession = user => createAuthenticatedSession(serverFactory, sinkFactory, refsFactory, store, user),
      validateSession = (user, sessionId) =>
        store.getUser(sessionId).flatMap {
          case Some(stored) if stored === user => Async[F].pure(Right(()))
          case Some(_)                         =>
            logger.info(Map("sessionId" -> sessionId, "reason" -> "credential-mismatch"))("authorization rejected") *>
              Forbidden("Credential mismatch").map(Left(_))
          case None =>
            logger.info(Map("sessionId" -> sessionId, "reason" -> "session-not-found"))("authorization rejected") *>
              NotFound("Session not found").map(Left(_))
        },
      store = store
    )

  /** Unified route builder for both the unauthenticated and authenticated paths.
    *
    * Three injection points capture the differences between the two flavours:
    *
    *   - `authCheck` runs once per request before any session lookup. `Left` short-circuits the response;
    *     `Right(authState)` threads through to subsequent steps. The unauthenticated path uses `A = Unit` and always
    *     returns `Right(())`.
    *   - `initSession` creates a brand-new session on `initialize`. The authenticated path closes over the user so it
    *     can bind the identity in the session store.
    *   - `validateSession` runs once the request's `Mcp-Session-Id` is in hand (but before `store.get(sid)`). `Left`
    *     short-circuits; `Right(())` proceeds. The authenticated path enforces "the credentials match what was bound at
    *     `initialize` time"; the unauthenticated path always returns `Right(())`.
    *
    * The DELETE path intentionally consults `validateSession` first so the authenticated impl can reject credential
    * mismatches before the session is touched. On `Right(())`, the existing session (if any) is cancelled and removed —
    * matching the original unauthenticated behaviour of silently 200-ing on DELETE of a missing session.
    */
  private def buildRoutes[F[_]: Async: LoggerFactory, A](
      authCheck: Request[F] => F[Either[Response[F], A]],
      initSession: A => F[McpSession[F]],
      validateSession: (A, String) => F[Either[Response[F], Unit]],
      store: SessionStore[F]
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*
    val logger = LoggerFactory[F].getLoggerFromName("net.andimiller.mcp.http4s.StreamableHttpTransport")

    HttpRoutes.of[F] {
      // ── POST /mcp ────────────────────────────────────────────────────
      case req @ POST -> Root / "mcp" =>
        authCheck(req).flatMap {
          case Left(resp)       => Async[F].pure(resp)
          case Right(authState) =>
            readBody(req).flatMap {
              case Left(err) =>
                logger.info(Map("error" -> err))("JSON-RPC parse failure") *> BadRequest(err)
              case Right(message) =>
                message match
                  case r @ Message.Request(_, _, "initialize", _) =>
                    initSession(authState).flatMap { session =>
                      session.handler.handle(r).flatMap {
                        case Some(response) =>
                          Ok(response.asJson.noSpaces).map(
                            _.putHeaders(
                              Header.Raw(mcpSessionId, session.id),
                              `Content-Type`(MediaType.application.json)
                            )
                          )
                        case None => Accepted()
                      }
                    }

                  case msg =>
                    getSessionId(req) match
                      case None      => BadRequest("Missing Mcp-Session-Id header")
                      case Some(sid) =>
                        validateSession(authState, sid).flatMap {
                          case Left(resp) => Async[F].pure(resp)
                          case Right(())  =>
                            store.get(sid).flatMap {
                              case None          => NotFound("Session not found")
                              case Some(session) =>
                                msg match
                                  case _: Message.Notification | _: Message.Response =>
                                    session.handler.handle(msg).as(Response[F](Status.Accepted))
                                  case _: Message.Request =>
                                    session.handler.handle(msg).flatMap {
                                      case Some(response) =>
                                        Ok(response.asJson.noSpaces)
                                          .map(_.withContentType(`Content-Type`(MediaType.application.json)))
                                      case None => Accepted()
                                    }
                            }
                        }
            }
        }

      // ── GET /mcp ─────────────────────────────────────────────────────
      case req @ GET -> Root / "mcp" =>
        authCheck(req).flatMap {
          case Left(resp)       => Async[F].pure(resp)
          case Right(authState) =>
            getSessionId(req) match
              case None      => BadRequest("Missing Mcp-Session-Id header")
              case Some(sid) =>
                validateSession(authState, sid).flatMap {
                  case Left(resp) => Async[F].pure(resp)
                  case Right(())  =>
                    store.get(sid).flatMap {
                      case None          => NotFound("Session not found")
                      case Some(session) =>
                        val sseStream: Stream[F, String] = session.clientChannel.subscribe.map { msg =>
                          s"event: message\ndata: ${msg.asJson.noSpaces}\n\n"
                        }
                        Ok(sseStream).map(_.withContentType(`Content-Type`(eventStreamMediaType)))
                    }
                }
        }

      // ── DELETE /mcp ──────────────────────────────────────────────────
      case req @ DELETE -> Root / "mcp" =>
        authCheck(req).flatMap {
          case Left(resp)       => Async[F].pure(resp)
          case Right(authState) =>
            getSessionId(req) match
              case None      => BadRequest("Missing Mcp-Session-Id header")
              case Some(sid) =>
                validateSession(authState, sid).flatMap {
                  case Left(resp) => Async[F].pure(resp)
                  case Right(())  =>
                    store.get(sid).flatMap(_.traverse_(_.clientChannel.cancellation.cancelAll)) *>
                      store.remove(sid) *>
                      logger.info(Map("sessionId" -> sid, "reason" -> "client-delete"))("session destroyed") *>
                      Ok("Session terminated")
                }
        }
    }
