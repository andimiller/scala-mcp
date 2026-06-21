package net.andimiller.mcp.core.logging

import cats.effect.kernel.Sync

import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

/** An importable no-op `LoggerFactory[F]` for callers who don't want to wire a real logging backend.
  *
  * scala-mcp's public APIs require `LoggerFactory[F]` in implicit scope. Production callers usually want to wire
  * `Slf4jFactory.create[IO]` (JVM, from `log4cats-slf4j`) so logs actually go somewhere. For one-off scripts, examples,
  * test fixtures, or any caller that doesn't care about logging, add a single import to get a silent default:
  *
  * {{{
  * import net.andimiller.mcp.core.logging.NoOpLogging.given
  * }}}
  *
  * Any user-defined `given LoggerFactory[F]` in narrower scope takes priority over the import.
  */
object NoOpLogging:

  given noOpLoggerFactory[F[_]: Sync]: LoggerFactory[F] = NoOpFactory[F]
