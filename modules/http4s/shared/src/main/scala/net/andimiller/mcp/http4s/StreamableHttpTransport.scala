package net.andimiller.mcp.http4s

import cats.Eq
import cats.effect.kernel.{Async, Ref, Resource}
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*
import fs2.Stream
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.server.{ClientChannel, NotificationSink, RequestHandler, Server, SessionContext}
import net.andimiller.mcp.core.state.SessionRefs

/**
 * Streamable HTTP transport for MCP (spec 2025-03-26).
 *
 * Provides three endpoints on a single path:
 *  - '''POST /mcp''' — send JSON-RPC requests/notifications
 *  - '''GET  /mcp''' — open an SSE stream for server-initiated notifications
 *  - '''DELETE /mcp''' — terminate a session
 *
 * Sessions are created on `initialize` and identified by the `Mcp-Session-Id` header. The
 * `serverFactory` receives a [[SessionContext]] containing the session id, the bidirectional
 * [[ClientChannel]], and a per-session [[SessionRefs]].
 */
object StreamableHttpTransport:

  private val mcpSessionId = ci"Mcp-Session-Id"
  private val eventStreamMediaType = new MediaType("text", "event-stream")

  /** Convenience: build routes with default in-memory sinks, refs, and session store. */
  def routes[F[_]: Async: UUIDGen](
    serverFactory: (String, SessionContext[F]) => F[Server[F]]
  ): Resource[F, HttpRoutes[F]] =
    Resource.eval(SessionStore.inMemory[F]).map { store =>
      buildRoutesFromInit(createSessionFromContext(serverFactory, defaultSinkFactory[F], defaultRefsFactory[F], store), store)
    }

  /**
   * Build routes with user-supplied per-session factories.
   *
   * @param serverFactory  receives the new session's id and [[SessionContext]] and produces a `Server[F]`
   * @param sinkFactory    builds a [[NotificationSink]] per session id (default: in-memory)
   * @param refsFactory    builds a [[SessionRefs]] per session id (default: in-memory)
   * @param store          where to persist [[McpSession]]s by id
   */
  def routes[F[_]: Async: UUIDGen](
    serverFactory: (String, SessionContext[F]) => F[Server[F]],
    sinkFactory:   String => Resource[F, NotificationSink[F]],
    refsFactory:   String => SessionRefs[F],
    store:         SessionStore[F]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(buildRoutesFromInit(createSessionFromContext(serverFactory, sinkFactory, refsFactory, store), store))

  /**
   * Build authenticated HTTP routes. On `initialize`, the extracted user identity is stored
   * alongside the session; subsequent requests must present credentials for the same user
   * (enforced via `Eq[U]`).
   */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate:   Request[F] => F[Option[U]],
    serverFactory:  (String, U, SessionContext[F]) => F[Server[F]],
    onUnauthorized: F[Response[F]],
    sinkFactory:    String => Resource[F, NotificationSink[F]],
    refsFactory:    String => SessionRefs[F],
    store:          AuthenticatedSessionStore[F, U]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(buildAuthenticatedRoutes(authenticate, serverFactory, sinkFactory, refsFactory, store, onUnauthorized))

  /** Convenience: authenticated routes with default in-memory factories and store. */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate:   Request[F] => F[Option[U]],
    serverFactory:  (String, U, SessionContext[F]) => F[Server[F]],
    onUnauthorized: F[Response[F]]
  ): Resource[F, HttpRoutes[F]] =
    Resource.eval(AuthenticatedSessionStore.inMemory[F, U]).map { store =>
      buildAuthenticatedRoutes(authenticate, serverFactory, defaultSinkFactory[F], defaultRefsFactory[F], store, onUnauthorized)
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
  private def createSessionFromContext[F[_]: Async: UUIDGen](
    serverFactory: (String, SessionContext[F]) => F[Server[F]],
    sinkFactory:   String => Resource[F, NotificationSink[F]],
    refsFactory:   String => SessionRefs[F],
    store:         SessionStore[F]
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory(id).allocated
      (sink, _)      = sinkPair
      ccPair        <- ClientChannel.fromSink[F](sink).allocated
      (cc, _)        = ccPair
      ctx            = SessionContext[F](id, cc, refsFactory(id))
      server        <- serverFactory(id, ctx)
      handler        = new RequestHandler[F](server, cc.requester)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, cc, subscriptions)
      _             <- store.put(session)
    yield session

  /** Same as [[createSessionFromContext]] but additionally binds the user identity in the
   *  authenticated session store. */
  private def createAuthenticatedSession[F[_]: Async: UUIDGen, U](
    serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
    sinkFactory:   String => Resource[F, NotificationSink[F]],
    refsFactory:   String => SessionRefs[F],
    store:         AuthenticatedSessionStore[F, U],
    user:          U
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory(id).allocated
      (sink, _)      = sinkPair
      ccPair        <- ClientChannel.fromSink[F](sink).allocated
      (cc, _)        = ccPair
      ctx            = SessionContext[F](id, cc, refsFactory(id))
      server        <- serverFactory(id, user, ctx)
      handler        = new RequestHandler[F](server, cc.requester)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, cc, subscriptions)
      _             <- store.put(session)
      _             <- store.putUser(id, user)
    yield session

  // ── Routes assembly ────────────────────────────────────────────────

  /** The actual routing shape, parameterised on how a new session is initialised. */
  private def buildRoutesFromInit[F[_]: Async](
    initSession: F[McpSession[F]],
    store:       SessionStore[F]
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F] {
      // ── POST /mcp ────────────────────────────────────────────────────
      case req @ POST -> Root / "mcp" =>
        readBody(req).flatMap {
          case Left(err) =>
            BadRequest(err)

          case Right(message) =>
            message match
              case r @ Message.Request(_, _, "initialize", _) =>
                initSession.flatMap { session =>
                  session.handler.handle(r).flatMap {
                    case Some(response) =>
                      Ok(response.asJson.noSpaces).map(_.putHeaders(
                        Header.Raw(mcpSessionId, session.id),
                        `Content-Type`(MediaType.application.json)
                      ))
                    case None =>
                      Accepted()
                  }
                }

              case msg =>
                getSessionId(req) match
                  case None => BadRequest("Missing Mcp-Session-Id header")
                  case Some(sid) =>
                    store.get(sid).flatMap {
                      case None => NotFound("Session not found")
                      case Some(session) =>
                        msg match
                          case _: Message.Notification | _: Message.Response =>
                            session.handler.handle(msg).as(Response[F](Status.Accepted))
                          case _: Message.Request =>
                            session.handler.handle(msg).flatMap {
                              case Some(response) =>
                                Ok(response.asJson.noSpaces).map(_.withContentType(`Content-Type`(MediaType.application.json)))
                              case None =>
                                Accepted()
                            }
                    }
        }

      // ── GET /mcp ─────────────────────────────────────────────────────
      case req @ GET -> Root / "mcp" =>
        getSessionId(req) match
          case None => BadRequest("Missing Mcp-Session-Id header")
          case Some(sid) =>
            store.get(sid).flatMap {
              case None => NotFound("Session not found")
              case Some(session) =>
                val sseStream: Stream[F, String] = session.clientChannel.subscribe.map { msg =>
                  s"event: message\ndata: ${msg.asJson.noSpaces}\n\n"
                }
                Ok(sseStream).map(_.withContentType(`Content-Type`(eventStreamMediaType)))
            }

      // ── DELETE /mcp ──────────────────────────────────────────────────
      case req @ DELETE -> Root / "mcp" =>
        getSessionId(req) match
          case None => BadRequest("Missing Mcp-Session-Id header")
          case Some(sid) => store.remove(sid) *> Ok("Session terminated")
    }

  /** Authenticated variant — every request runs through `authenticate`, and on subsequent
   *  requests the stored user identity is checked against the new credentials. */
  private def buildAuthenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate:   Request[F] => F[Option[U]],
    serverFactory:  (String, U, SessionContext[F]) => F[Server[F]],
    sinkFactory:    String => Resource[F, NotificationSink[F]],
    refsFactory:    String => SessionRefs[F],
    store:          AuthenticatedSessionStore[F, U],
    onUnauthorized: F[Response[F]]
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F] {
      // ── POST /mcp (authenticated) ──────────────────────────────────
      case req @ POST -> Root / "mcp" =>
        authenticate(req).flatMap {
          case None => onUnauthorized
          case Some(user) =>
            readBody(req).flatMap {
              case Left(err) => BadRequest(err)
              case Right(message) =>
                message match
                  case r @ Message.Request(_, _, "initialize", _) =>
                    createAuthenticatedSession(serverFactory, sinkFactory, refsFactory, store, user).flatMap { session =>
                      session.handler.handle(r).flatMap {
                        case Some(response) =>
                          Ok(response.asJson.noSpaces).map(_.putHeaders(
                            Header.Raw(mcpSessionId, session.id),
                            `Content-Type`(MediaType.application.json)
                          ))
                        case None => Accepted()
                      }
                    }

                  case msg =>
                    getSessionId(req) match
                      case None => BadRequest("Missing Mcp-Session-Id header")
                      case Some(sid) =>
                        store.get(sid).flatMap {
                          case None => NotFound("Session not found")
                          case Some(session) =>
                            store.getUser(sid).flatMap {
                              case Some(storedUser) if storedUser === user =>
                                msg match
                                  case _: Message.Notification | _: Message.Response =>
                                    session.handler.handle(msg).as(Response[F](Status.Accepted))
                                  case _: Message.Request =>
                                    session.handler.handle(msg).flatMap {
                                      case Some(response) =>
                                        Ok(response.asJson.noSpaces).map(_.withContentType(`Content-Type`(MediaType.application.json)))
                                      case None => Accepted()
                                    }
                              case Some(_) => Forbidden("Credential mismatch")
                              case None    => NotFound("Session not found")
                            }
                        }
            }
        }

      // ── GET /mcp (authenticated) ──────────────────────────────────
      case req @ GET -> Root / "mcp" =>
        authenticate(req).flatMap {
          case None => onUnauthorized
          case Some(user) =>
            getSessionId(req) match
              case None => BadRequest("Missing Mcp-Session-Id header")
              case Some(sid) =>
                store.get(sid).flatMap {
                  case None => NotFound("Session not found")
                  case Some(session) =>
                    store.getUser(sid).flatMap {
                      case Some(storedUser) if storedUser === user =>
                        val sseStream: Stream[F, String] = session.clientChannel.subscribe.map { msg =>
                          s"event: message\ndata: ${msg.asJson.noSpaces}\n\n"
                        }
                        Ok(sseStream).map(_.withContentType(`Content-Type`(eventStreamMediaType)))
                      case Some(_) => Forbidden("Credential mismatch")
                      case None    => NotFound("Session not found")
                    }
                }
        }

      // ── DELETE /mcp (authenticated) ────────────────────────────────
      case req @ DELETE -> Root / "mcp" =>
        authenticate(req).flatMap {
          case None => onUnauthorized
          case Some(user) =>
            getSessionId(req) match
              case None => BadRequest("Missing Mcp-Session-Id header")
              case Some(sid) =>
                store.getUser(sid).flatMap {
                  case Some(storedUser) if storedUser === user =>
                    store.remove(sid) *> Ok("Session terminated")
                  case Some(_) => Forbidden("Credential mismatch")
                  case None    => NotFound("Session not found")
                }
        }
    }
