package net.andimiller.mcp.http4s

import cats.effect.{IO, Resource}
import cats.syntax.semigroupk.*
import com.comcast.ip4s.*
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.dsl.io.*
import net.andimiller.mcp.core.server.{NotificationSink, Server}

private[http4s] object HttpMcpRouting:

  def serve(
    serverFactory: NotificationSink[IO] => IO[Server[IO]],
    host: Host,
    port: Port,
    explorerEnabled: Boolean,
    rootRedirectToExplorer: Boolean
  ): Resource[IO, org.http4s.server.Server] =
    StreamableHttpTransport.routes[IO](serverFactory).flatMap { mcpRoutes =>
      val redirectRoute = HttpRoutes.of[IO] {
        case GET -> Root => IO.pure(Response[IO](Status.Found).withHeaders(Location(uri"/explorer/index.html")))
      }
      val routes =
        if explorerEnabled && rootRedirectToExplorer then Router("/" -> (redirectRoute <+> mcpRoutes), "/explorer" -> ExplorerRoutes[IO])
        else if explorerEnabled then Router("/" -> mcpRoutes, "/explorer" -> ExplorerRoutes[IO])
        else Router("/" -> mcpRoutes)
      val app = routes.orNotFound
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(app)
        .build
    }
