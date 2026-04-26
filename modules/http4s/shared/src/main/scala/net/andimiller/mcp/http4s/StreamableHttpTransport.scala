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
import net.andimiller.mcp.core.server.{CancellationRegistry, NotificationSink, RequestHandler, Server}

/**
 * Streamable HTTP transport for MCP (spec 2025-03-26).
 *
 * Provides three endpoints on a single path:
 *  - '''POST /mcp''' — send JSON-RPC requests/notifications
 *  - '''GET  /mcp''' — open an SSE stream for server-initiated notifications
 *  - '''DELETE /mcp''' — terminate a session
 *
 * Sessions are created on `initialize` and identified by the `Mcp-Session-Id` header.
 */
object StreamableHttpTransport:

  private val mcpSessionId = ci"Mcp-Session-Id"
  private val eventStreamMediaType = new MediaType("text", "event-stream")

  /** Default per-session cancellation factory — builds a local-only in-memory registry. */
  private def defaultCancelFactory[F[_]: Async]: String => Resource[F, CancellationRegistry[F]] =
    _ => Resource.eval(CancellationRegistry.create[F])

  /**
   * Build HTTP routes for the MCP streamable transport.
   *
   * @param serverFactory creates a [[Server]] given a [[NotificationSink]] for the session
   * @return an `HttpRoutes` resource that manages session lifecycle
   */
  def routes[F[_]: Async: UUIDGen](
    serverFactory: NotificationSink[F] => F[Server[F]]
  ): Resource[F, HttpRoutes[F]] =
    for
      sinkFactory <- Resource.pure(NotificationSink.create[F])
      store       <- Resource.eval(SessionStore.inMemory[F])
    yield buildRoutes(serverFactory, sinkFactory, store, defaultCancelFactory[F])

  /**
   * Build HTTP routes using an externally-provided [[SessionStore]].
   */
  def routes[F[_]: Async: UUIDGen](
    serverFactory: NotificationSink[F] => F[Server[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    store: SessionStore[F]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(buildRoutes(serverFactory, sinkFactory, store, defaultCancelFactory[F]))

  /**
   * Build HTTP routes with session-ID-aware factories.
   *
   * The session ID is passed to both `serverFactory` and `sinkFactory`,
   * allowing external backends (e.g. Redis) to key state by session.
   */
  def routes[F[_]: Async: UUIDGen](
    serverFactory: (String, NotificationSink[F]) => F[Server[F]],
    sinkFactory: String => Resource[F, NotificationSink[F]],
    store: SessionStore[F]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(buildRoutesWithId(serverFactory, sinkFactory, store, defaultCancelFactory[F]))

  /**
   * Build HTTP routes with session-ID-aware factories, including a factory for
   * per-session [[CancellationRegistry]] instances. Use this overload when
   * cancellation must route across multiple server nodes (e.g. via Redis pub/sub).
   */
  def routes[F[_]: Async: UUIDGen](
    serverFactory: (String, NotificationSink[F]) => F[Server[F]],
    sinkFactory: String => Resource[F, NotificationSink[F]],
    store: SessionStore[F],
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(buildRoutesWithId(serverFactory, sinkFactory, store, cancelFactory))

  private def getSessionId[F[_]](req: Request[F]): Option[String] =
    req.headers.get(mcpSessionId).map(_.head.value)

  private def buildRoutes[F[_]: Async: UUIDGen](
    serverFactory: NotificationSink[F] => F[Server[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    store: SessionStore[F],
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
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
              // Initialize: create a new session
              case r @ Message.Request(_, _, "initialize", _) =>
                createSession(serverFactory, sinkFactory, store, cancelFactory).flatMap { session =>
                  session.handler.handle(r).flatMap {
                    case Some(response) =>
                      Ok(response.asJson.noSpaces)
                        .map(_.putHeaders(
                          Header.Raw(mcpSessionId, session.id),
                          `Content-Type`(MediaType.application.json)
                        ))
                    case None =>
                      Accepted()
                  }
                }

              // All other messages: require an existing session
              case msg =>
                getSessionId(req) match
                  case None =>
                    BadRequest("Missing Mcp-Session-Id header")

                  case Some(sid) =>
                    store.get(sid).flatMap {
                      case None =>
                        NotFound("Session not found")

                      case Some(session) =>
                        msg match
                          case _: Message.Notification | _: Message.Response =>
                            session.handler.handle(msg).as(
                              Response[F](Status.Accepted)
                            )

                          case _: Message.Request =>
                            session.handler.handle(msg).flatMap {
                              case Some(response) =>
                                Ok(response.asJson.noSpaces)
                                  .map(_.withContentType(`Content-Type`(MediaType.application.json)))
                              case None =>
                                Accepted()
                            }
                    }
        }

      // ── GET /mcp ─────────────────────────────────────────────────────
      case req @ GET -> Root / "mcp" =>
        getSessionId(req) match
          case None =>
            BadRequest("Missing Mcp-Session-Id header")

          case Some(sid) =>
            store.get(sid).flatMap {
              case None =>
                NotFound("Session not found")

              case Some(session) =>
                val sseStream: Stream[F, String] = session.sink.subscribe.map { msg =>
                  s"event: message\ndata: ${msg.asJson.noSpaces}\n\n"
                }

                Ok(sseStream).map(_.withContentType(
                  `Content-Type`(eventStreamMediaType)
                ))
            }

      // ── DELETE /mcp ──────────────────────────────────────────────────
      case req @ DELETE -> Root / "mcp" =>
        getSessionId(req) match
          case None =>
            BadRequest("Missing Mcp-Session-Id header")

          case Some(sid) =>
            store.remove(sid) *> Ok("Session terminated")
    }

  private def buildRoutesWithId[F[_]: Async: UUIDGen](
    serverFactory: (String, NotificationSink[F]) => F[Server[F]],
    sinkFactory: String => Resource[F, NotificationSink[F]],
    store: SessionStore[F],
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
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
              // Initialize: create a new session
              case r @ Message.Request(_, _, "initialize", _) =>
                createSessionWithId(serverFactory, sinkFactory, store, cancelFactory).flatMap { session =>
                  session.handler.handle(r).flatMap {
                    case Some(response) =>
                      Ok(response.asJson.noSpaces)
                        .map(_.putHeaders(
                          Header.Raw(mcpSessionId, session.id),
                          `Content-Type`(MediaType.application.json)
                        ))
                    case None =>
                      Accepted()
                  }
                }

              // All other messages: require an existing session
              case msg =>
                getSessionId(req) match
                  case None =>
                    BadRequest("Missing Mcp-Session-Id header")

                  case Some(sid) =>
                    store.get(sid).flatMap {
                      case None =>
                        NotFound("Session not found")

                      case Some(session) =>
                        msg match
                          case _: Message.Notification | _: Message.Response =>
                            session.handler.handle(msg).as(
                              Response[F](Status.Accepted)
                            )

                          case _: Message.Request =>
                            session.handler.handle(msg).flatMap {
                              case Some(response) =>
                                Ok(response.asJson.noSpaces)
                                  .map(_.withContentType(`Content-Type`(MediaType.application.json)))
                              case None =>
                                Accepted()
                            }
                    }
        }

      // ── GET /mcp ─────────────────────────────────────────────────────
      case req @ GET -> Root / "mcp" =>
        getSessionId(req) match
          case None =>
            BadRequest("Missing Mcp-Session-Id header")

          case Some(sid) =>
            store.get(sid).flatMap {
              case None =>
                NotFound("Session not found")

              case Some(session) =>
                val sseStream: Stream[F, String] = session.sink.subscribe.map { msg =>
                  s"event: message\ndata: ${msg.asJson.noSpaces}\n\n"
                }

                Ok(sseStream).map(_.withContentType(
                  `Content-Type`(eventStreamMediaType)
                ))
            }

      // ── DELETE /mcp ──────────────────────────────────────────────────
      case req @ DELETE -> Root / "mcp" =>
        getSessionId(req) match
          case None =>
            BadRequest("Missing Mcp-Session-Id header")

          case Some(sid) =>
            store.remove(sid) *> Ok("Session terminated")
    }

  /**
   * Build authenticated HTTP routes for the MCP streamable transport.
   *
   * Every request is authenticated via `authenticate`. On `initialize`, the
   * extracted user identity is stored alongside the session. Subsequent
   * requests for that session must present credentials for the same user
   * (enforced via `Eq[U]`), preventing credential swapping mid-session.
   *
   * @param authenticate   extracts a user identity from the HTTP request
   * @param serverFactory  creates a [[Server]] given the authenticated user and a [[NotificationSink]]
   * @param onUnauthorized response to return when authentication fails
   * @tparam U user identity type (must have [[Eq]] for identity comparison)
   */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate: Request[F] => F[Option[U]],
    serverFactory: (U, NotificationSink[F]) => F[Server[F]],
    onUnauthorized: F[Response[F]]
  ): Resource[F, HttpRoutes[F]] =
    for
      sinkFactory <- Resource.pure(NotificationSink.create[F])
      store       <- Resource.eval(AuthenticatedSessionStore.inMemory[F, U])
    yield buildAuthenticatedRoutes(authenticate, serverFactory, sinkFactory, store, onUnauthorized, defaultCancelFactory[F])

  /**
   * Build authenticated HTTP routes using an externally-provided [[AuthenticatedSessionStore]].
   */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate: Request[F] => F[Option[U]],
    serverFactory: (U, NotificationSink[F]) => F[Server[F]],
    onUnauthorized: F[Response[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    store: AuthenticatedSessionStore[F, U]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(buildAuthenticatedRoutes(authenticate, serverFactory, sinkFactory, store, onUnauthorized, defaultCancelFactory[F]))

  /**
   * Build authenticated HTTP routes with session-ID-aware factories.
   */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate: Request[F] => F[Option[U]],
    serverFactory: (String, U, NotificationSink[F]) => F[Server[F]],
    onUnauthorized: F[Response[F]],
    sinkFactory: String => Resource[F, NotificationSink[F]],
    store: AuthenticatedSessionStore[F, U]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(buildAuthenticatedRoutesWithId(authenticate, serverFactory, sinkFactory, store, onUnauthorized, defaultCancelFactory[F]))

  /**
   * Build authenticated HTTP routes with session-ID-aware factories, including a
   * factory for per-session [[CancellationRegistry]] instances.
   */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate: Request[F] => F[Option[U]],
    serverFactory: (String, U, NotificationSink[F]) => F[Server[F]],
    onUnauthorized: F[Response[F]],
    sinkFactory: String => Resource[F, NotificationSink[F]],
    store: AuthenticatedSessionStore[F, U],
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(buildAuthenticatedRoutesWithId(authenticate, serverFactory, sinkFactory, store, onUnauthorized, cancelFactory))

  private def buildAuthenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate: Request[F] => F[Option[U]],
    serverFactory: (U, NotificationSink[F]) => F[Server[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    store: AuthenticatedSessionStore[F, U],
    onUnauthorized: F[Response[F]],
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
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
                    createAuthenticatedSession(serverFactory, sinkFactory, store, user, cancelFactory).flatMap { session =>
                      session.handler.handle(r).flatMap {
                        case Some(response) =>
                          Ok(response.asJson.noSpaces)
                            .map(_.putHeaders(
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
                                        Ok(response.asJson.noSpaces)
                                          .map(_.withContentType(`Content-Type`(MediaType.application.json)))
                                      case None => Accepted()
                                    }
                              case Some(_) =>
                                Forbidden("Credential mismatch")
                              case None =>
                                NotFound("Session not found")
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
                        val sseStream: Stream[F, String] = session.sink.subscribe.map { msg =>
                          s"event: message\ndata: ${msg.asJson.noSpaces}\n\n"
                        }
                        Ok(sseStream).map(_.withContentType(
                          `Content-Type`(eventStreamMediaType)
                        ))
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

  private def createAuthenticatedSession[F[_]: Async: UUIDGen, U](
    serverFactory: (U, NotificationSink[F]) => F[Server[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    store: AuthenticatedSessionStore[F, U],
    user: U,
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory.allocated
      (sink, _)      = sinkPair
      server        <- serverFactory(user, sink)
      regPair       <- cancelFactory(id).allocated
      (registry, _)  = regPair
      handler        = new RequestHandler[F](server, sink, registry)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, sink, subscriptions)
      _             <- store.put(session)
      _             <- store.putUser(id, user)
    yield session

  private def buildAuthenticatedRoutesWithId[F[_]: Async: UUIDGen, U: Eq](
    authenticate: Request[F] => F[Option[U]],
    serverFactory: (String, U, NotificationSink[F]) => F[Server[F]],
    sinkFactory: String => Resource[F, NotificationSink[F]],
    store: AuthenticatedSessionStore[F, U],
    onUnauthorized: F[Response[F]],
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
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
                    createAuthenticatedSessionWithId(serverFactory, sinkFactory, store, user, cancelFactory).flatMap { session =>
                      session.handler.handle(r).flatMap {
                        case Some(response) =>
                          Ok(response.asJson.noSpaces)
                            .map(_.putHeaders(
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
                                        Ok(response.asJson.noSpaces)
                                          .map(_.withContentType(`Content-Type`(MediaType.application.json)))
                                      case None => Accepted()
                                    }
                              case Some(_) =>
                                Forbidden("Credential mismatch")
                              case None =>
                                NotFound("Session not found")
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
                        val sseStream: Stream[F, String] = session.sink.subscribe.map { msg =>
                          s"event: message\ndata: ${msg.asJson.noSpaces}\n\n"
                        }
                        Ok(sseStream).map(_.withContentType(
                          `Content-Type`(eventStreamMediaType)
                        ))
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

  private def createAuthenticatedSessionWithId[F[_]: Async: UUIDGen, U](
    serverFactory: (String, U, NotificationSink[F]) => F[Server[F]],
    sinkFactory: String => Resource[F, NotificationSink[F]],
    store: AuthenticatedSessionStore[F, U],
    user: U,
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory(id).allocated
      (sink, _)      = sinkPair
      server        <- serverFactory(id, user, sink)
      regPair       <- cancelFactory(id).allocated
      (registry, _)  = regPair
      handler        = new RequestHandler[F](server, sink, registry)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, sink, subscriptions)
      _             <- store.put(session)
      _             <- store.putUser(id, user)
    yield session

  private def readBody[F[_]: Async](req: Request[F]): F[Either[String, Message]] =
    req.bodyText.compile.string.map { body =>
      decode[Message](body).leftMap(err => s"Invalid JSON-RPC message: ${err.getMessage}")
    }

  private def createSession[F[_]: Async: UUIDGen](
    serverFactory: NotificationSink[F] => F[Server[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    store: SessionStore[F],
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory.allocated
      (sink, _)      = sinkPair
      server        <- serverFactory(sink)
      regPair       <- cancelFactory(id).allocated
      (registry, _)  = regPair
      handler        = new RequestHandler[F](server, sink, registry)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, sink, subscriptions)
      _             <- store.put(session)
    yield session

  private def createSessionWithId[F[_]: Async: UUIDGen](
    serverFactory: (String, NotificationSink[F]) => F[Server[F]],
    sinkFactory: String => Resource[F, NotificationSink[F]],
    store: SessionStore[F],
    cancelFactory: String => Resource[F, CancellationRegistry[F]]
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory(id).allocated
      (sink, _)      = sinkPair
      server        <- serverFactory(id, sink)
      regPair       <- cancelFactory(id).allocated
      (registry, _)  = regPair
      handler        = new RequestHandler[F](server, sink, registry)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, sink, subscriptions)
      _             <- store.put(session)
    yield session
