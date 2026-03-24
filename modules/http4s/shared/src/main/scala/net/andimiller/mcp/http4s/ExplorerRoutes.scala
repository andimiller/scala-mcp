package net.andimiller.mcp.http4s

import cats.effect.kernel.Async
import org.http4s.HttpRoutes
import org.http4s.server.staticcontent.resourceServiceBuilder

object ExplorerRoutes:
  def apply[F[_]: Async]: HttpRoutes[F] =
    resourceServiceBuilder[F]("/explorer").toRoutes
