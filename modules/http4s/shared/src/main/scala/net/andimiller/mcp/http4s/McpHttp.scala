package net.andimiller.mcp.http4s

import cats.Eq
import cats.effect.{IO, Resource}
import cats.effect.kernel.Async
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.server.{CapabilityTracker, NotificationSink, RequestHandler, Server as McpServer, ServerBuilder}
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{`Content-Type`, Location}
import org.http4s.implicits.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.typelevel.ci.*

object McpHttp:

  // ── Builder entry points ──────────────────────────────────────────

  def basic[F[_]: Async]: BasicMcpHttpBuilder[F] =
    new BasicMcpHttpBuilder[F](
      mName = "",
      mVersion = "",
      mConfig = McpHttpConfig(),
      mTools = Vector.empty,
      mResources = Vector.empty,
      mResourceTemplates = Vector.empty,
      mPrompts = Vector.empty,
      mCaps = CapabilityTracker.empty
    )

  def streaming[F[_]: Async]: StreamingMcpHttpBuilder[F, Unit] =
    new StreamingMcpHttpBuilder[F, Unit](
      mName = "",
      mVersion = "",
      mConfig = McpHttpConfig(),
      mAuthInfo = None,
      mStatefulCreators = Vector.empty,
      mStatefulExternalCreators = Vector.empty,
      mAuthExtractor = None,
      mPlainTools = Vector.empty,
      mContextToolResolvers = Vector.empty,
      mPlainResources = Vector.empty,
      mContextResourceResolvers = Vector.empty,
      mPlainResourceTemplates = Vector.empty,
      mContextResourceTemplateResolvers = Vector.empty,
      mPlainPrompts = Vector.empty,
      mContextPromptResolvers = Vector.empty,
      mCaps = CapabilityTracker.empty,
      mSessionStore = None,
      mSinkFactory = None,
      mSessionRefsFactory = None,
      mSessionStoreFactory = None,
      mCancellationRegistryFactory = None
    )

  // ── Simple HTTP transport (no sessions, no SSE) ──────────────────

  def routes[F[_]: Async](server: F[McpServer[F]]): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*
    HttpRoutes.of[F] {
      case req @ POST -> Root / "mcp" =>
        server.flatMap { s =>
          val handler = new RequestHandler[F](s)
          req.bodyText.compile.string.flatMap { body =>
            decode[Message](body) match
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
          }
        }
    }

  // ── Streamable HTTP transport (sessions + SSE) ───────────────────

  def streamableRoutes[F[_]: Async: UUIDGen](
    newSession: NotificationSink[F] => F[McpServer[F]]
  ): Resource[F, HttpRoutes[F]] =
    StreamableHttpTransport.routes[F](newSession)

  def authenticatedStreamableRoutes[F[_]: Async: UUIDGen, U: Eq](
    authenticate: Request[F] => F[Option[U]],
    newSession: (U, NotificationSink[F]) => F[McpServer[F]],
    onUnauthorized: F[Response[F]]
  ): Resource[F, HttpRoutes[F]] =
    StreamableHttpTransport.authenticatedRoutes[F, U](authenticate, newSession, onUnauthorized)

  // ── Full server convenience (IO only) ──────────────────────────────

  def serve(
    server: McpServer[IO],
    config: McpHttpConfig = McpHttpConfig()
  ): Resource[IO, org.http4s.server.Server] =
    val mcpRoutes = McpHttp.routes[IO](IO.pure(server))
    val app = buildApp(mcpRoutes, config)
    EmberServerBuilder.default[IO]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpApp(app)
      .build

  def serveStreamable(
    newSession: NotificationSink[IO] => IO[McpServer[IO]],
    config: McpHttpConfig = McpHttpConfig()
  ): Resource[IO, org.http4s.server.Server] =
    StreamableHttpTransport.routes[IO](newSession).flatMap { mcpRoutes =>
      val app = buildApp(mcpRoutes, config)
      EmberServerBuilder.default[IO]
        .withHost(config.host)
        .withPort(config.port)
        .withHttpApp(app)
        .build
    }

  def serveStreamableAuthenticated[U: Eq](
    authenticate: Request[IO] => IO[Option[U]],
    newSession: (U, NotificationSink[IO]) => IO[McpServer[IO]],
    onUnauthorized: Response[IO],
    config: McpHttpConfig = McpHttpConfig()
  ): Resource[IO, org.http4s.server.Server] =
    StreamableHttpTransport.authenticatedRoutes[IO, U](authenticate, newSession, IO(onUnauthorized)).flatMap { mcpRoutes =>
      val app = buildApp(mcpRoutes, config)
      EmberServerBuilder.default[IO]
        .withHost(config.host)
        .withPort(config.port)
        .withHttpApp(app)
        .build
    }

  // ── Internal helpers ──────────────────────────────────────────────

  private[http4s] def buildApp(mcpRoutes: HttpRoutes[IO], config: McpHttpConfig): HttpApp[IO] =
    val dsl = Http4sDsl[IO]
    import dsl.*
    val redirectRoute = HttpRoutes.of[IO] {
      case GET -> Root => IO.pure(Response[IO](Status.Found).withHeaders(Location(uri"/explorer/index.html")))
    }
    val routes =
      if config.explorerEnabled && config.rootRedirectToExplorer then
        Router("/" -> (redirectRoute <+> mcpRoutes), "/explorer" -> ExplorerRoutes[IO])
      else if config.explorerEnabled then
        Router("/" -> mcpRoutes, "/explorer" -> ExplorerRoutes[IO])
      else
        Router("/" -> mcpRoutes)
    routes.orNotFound
