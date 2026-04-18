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
import net.andimiller.mcp.core.server.{NotificationSink, RequestHandler, Server}

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
      sessions    <- Resource.eval(Ref.of[F, Map[String, McpSession[F]]](Map.empty))
    yield buildRoutes(serverFactory, sinkFactory, sessions)

  private def getSessionId[F[_]](req: Request[F]): Option[String] =
    req.headers.get(mcpSessionId).map(_.head.value)

  private def lookupSession[F[_]: Async](
    sessions: Ref[F, Map[String, McpSession[F]]],
    sessionId: String
  ): F[Option[McpSession[F]]] =
    sessions.get.map(_.get(sessionId))

  private def buildRoutes[F[_]: Async: UUIDGen](
    serverFactory: NotificationSink[F] => F[Server[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    sessions: Ref[F, Map[String, McpSession[F]]]
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
                createSession(serverFactory, sinkFactory, sessions).flatMap { session =>
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
                    lookupSession(sessions, sid).flatMap {
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
            lookupSession(sessions, sid).flatMap {
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
            sessions.update(_ - sid) *> Ok("Session terminated")
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
      sessions    <- Resource.eval(Ref.of[F, Map[String, McpSession[F]]](Map.empty))
      userMap     <- Resource.eval(Ref.of[F, Map[String, U]](Map.empty))
    yield buildAuthenticatedRoutes(authenticate, serverFactory, sinkFactory, sessions, userMap, onUnauthorized)

  private def buildAuthenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate: Request[F] => F[Option[U]],
    serverFactory: (U, NotificationSink[F]) => F[Server[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    sessions: Ref[F, Map[String, McpSession[F]]],
    userMap: Ref[F, Map[String, U]],
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
                    createAuthenticatedSession(serverFactory, sinkFactory, sessions, userMap, user).flatMap { session =>
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
                        lookupSession(sessions, sid).flatMap {
                          case None => NotFound("Session not found")
                          case Some(session) =>
                            userMap.get.map(_.get(sid)).flatMap {
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
                lookupSession(sessions, sid).flatMap {
                  case None => NotFound("Session not found")
                  case Some(session) =>
                    userMap.get.map(_.get(sid)).flatMap {
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
                userMap.get.map(_.get(sid)).flatMap {
                  case Some(storedUser) if storedUser === user =>
                    sessions.update(_ - sid) *> userMap.update(_ - sid) *> Ok("Session terminated")
                  case Some(_) => Forbidden("Credential mismatch")
                  case None    => NotFound("Session not found")
                }
        }
    }

  private def createAuthenticatedSession[F[_]: Async: UUIDGen, U](
    serverFactory: (U, NotificationSink[F]) => F[Server[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    sessions: Ref[F, Map[String, McpSession[F]]],
    userMap: Ref[F, Map[String, U]],
    user: U
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory.allocated
      (sink, _)      = sinkPair
      server        <- serverFactory(user, sink)
      handler        = new RequestHandler[F](server)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, sink, subscriptions)
      _             <- sessions.update(_ + (id -> session))
      _             <- userMap.update(_ + (id -> user))
    yield session

  private def readBody[F[_]: Async](req: Request[F]): F[Either[String, Message]] =
    req.bodyText.compile.string.map { body =>
      decode[Message](body).leftMap(err => s"Invalid JSON-RPC message: ${err.getMessage}")
    }

  private def createSession[F[_]: Async: UUIDGen](
    serverFactory: NotificationSink[F] => F[Server[F]],
    sinkFactory: Resource[F, NotificationSink[F]],
    sessions: Ref[F, Map[String, McpSession[F]]]
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory.allocated
      (sink, _)      = sinkPair
      server        <- serverFactory(sink)
      handler        = new RequestHandler[F](server)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, sink, subscriptions)
      _             <- sessions.update(_ + (id -> session))
    yield session
