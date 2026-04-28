package net.andimiller.mcp.http4s

import cats.Eq
import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.UUIDGen
import cats.syntax.all.*

import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.server.CancellationRegistry
import net.andimiller.mcp.core.server.CapabilityTracker
import net.andimiller.mcp.core.server.RequestHandler
import net.andimiller.mcp.core.server.ServerRequester
import net.andimiller.mcp.core.server.SessionContext
import net.andimiller.mcp.core.server.Server as McpServer

import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Location
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.http4s.server.Router

object McpHttp:

  // ── Builder entry points ──────────────────────────────────────────

  def basic[F[_]: Async]: BasicMcpHttpBuilder[F] =
    new BasicMcpHttpBuilder[F](
      mName = "", mVersion = "", mConfig = McpHttpConfig(), mTools = Vector.empty, mResources = Vector.empty,
      mResourceTemplates = Vector.empty, mPrompts = Vector.empty, mCaps = CapabilityTracker.empty
    )

  def streaming[F[_]: Async]: StreamingMcpHttpBuilder[F, Unit] =
    new StreamingMcpHttpBuilder[F, Unit](
      mName = "", mVersion = "", mConfig = McpHttpConfig(), mAuthInfo = None, mStatefulCreators = Vector.empty,
      mAuthExtractor = None, mPlainTools = Vector.empty, mContextToolResolvers = Vector.empty,
      mPlainResources = Vector.empty, mContextResourceResolvers = Vector.empty, mPlainResourceTemplates = Vector.empty,
      mContextResourceTemplateResolvers = Vector.empty, mPlainPrompts = Vector.empty,
      mContextPromptResolvers = Vector.empty, mCaps = CapabilityTracker.empty, mSessionStore = None,
      mSinkFactory = None, mSessionRefsFactory = None, mSessionStoreFactory = None
    )

  // ── Simple HTTP transport (no sessions, no SSE) ──────────────────

  def routes[F[_]: Async](server: F[McpServer[F]]): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*
    HttpRoutes.of[F] { case req @ POST -> Root / "mcp" =>
      for
        s         <- server
        requester <- ServerRequester.noop[F]
        cancel     = CancellationRegistry.noop[F]
        handler    = new RequestHandler[F](s, requester, cancel)
        body      <- req.bodyText.compile.string
        resp      <- decode[Message](body) match
                  case Right(message) =>
                    handler.handle(message).flatMap {
                      case Some(response) =>
                        Ok(response.asJson.noSpaces)
                          .map(_.withContentType(`Content-Type`(MediaType.application.json)))
                      case None =>
                        Accepted()
                    }
                  case Left(err) =>
                    BadRequest(s"Invalid JSON-RPC message: ${err.getMessage}")
      yield resp
    }

  // ── Streamable HTTP transport (sessions + SSE) ───────────────────

  def streamableRoutes[F[_]: Async: UUIDGen](
      newSession: SessionContext[F] => F[McpServer[F]]
  ): Resource[F, HttpRoutes[F]] =
    StreamableHttpTransport.routes[F]((_, ctx) => newSession(ctx))

  def authenticatedStreamableRoutes[F[_]: Async: UUIDGen, U: Eq](
      authenticate: Request[F] => F[Option[U]],
      newSession: (U, SessionContext[F]) => F[McpServer[F]],
      onUnauthorized: F[Response[F]]
  ): Resource[F, HttpRoutes[F]] =
    StreamableHttpTransport.authenticatedRoutes[F, U](
      authenticate,
      (_, user, ctx) => newSession(user, ctx),
      onUnauthorized
    )

  // ── Full server convenience (IO only) ──────────────────────────────

  def serve(
      server: McpServer[IO],
      config: McpHttpConfig = McpHttpConfig()
  ): Resource[IO, org.http4s.server.Server] =
    val mcpRoutes = McpHttp.routes[IO](IO.pure(server))
    val app       = buildApp(mcpRoutes, config)
    EmberServerBuilder
      .default[IO]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpApp(app)
      .build

  def serveStreamable(
      newSession: SessionContext[IO] => IO[McpServer[IO]],
      config: McpHttpConfig = McpHttpConfig()
  ): Resource[IO, org.http4s.server.Server] =
    StreamableHttpTransport.routes[IO]((_, ctx) => newSession(ctx)).flatMap { mcpRoutes =>
      val app = buildApp(mcpRoutes, config)
      EmberServerBuilder
        .default[IO]
        .withHost(config.host)
        .withPort(config.port)
        .withHttpApp(app)
        .build
    }

  def serveStreamableAuthenticated[U: Eq](
      authenticate: Request[IO] => IO[Option[U]],
      newSession: (U, SessionContext[IO]) => IO[McpServer[IO]],
      onUnauthorized: Response[IO],
      config: McpHttpConfig = McpHttpConfig()
  ): Resource[IO, org.http4s.server.Server] =
    StreamableHttpTransport
      .authenticatedRoutes[IO, U](
        authenticate,
        (_, user, ctx) => newSession(user, ctx),
        IO(onUnauthorized)
      )
      .flatMap { mcpRoutes =>
        val app = buildApp(mcpRoutes, config)
        EmberServerBuilder
          .default[IO]
          .withHost(config.host)
          .withPort(config.port)
          .withHttpApp(app)
          .build
      }

  // ── Internal helpers ──────────────────────────────────────────────

  private[http4s] def buildApp(mcpRoutes: HttpRoutes[IO], config: McpHttpConfig): HttpApp[IO] =
    val dsl = Http4sDsl[IO]
    import dsl.*
    val redirectRoute = HttpRoutes.of[IO] { case GET -> Root =>
      IO.pure(Response[IO](Status.Found).withHeaders(Location(uri"/explorer/index.html")))
    }
    val routes =
      if config.explorerEnabled && config.rootRedirectToExplorer then
        Router("/" -> (redirectRoute <+> mcpRoutes), "/explorer" -> ExplorerRoutes[IO])
      else if config.explorerEnabled then Router("/" -> mcpRoutes, "/explorer" -> ExplorerRoutes[IO])
      else Router("/"                                -> mcpRoutes)
    routes.orNotFound
