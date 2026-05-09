package net.andimiller.mcp.http4s

import cats.effect.kernel.Async

import org.http4s.HttpRoutes

// Native binaries don't ship the explorer SPA — return empty routes so callers that
// flip on `config.explorerEnabled` still link cleanly. JVM serves from classpath resources;
// JS serves from the filesystem.
object ExplorerRoutes:

  def apply[F[_]: Async]: HttpRoutes[F] = HttpRoutes.empty[F]
