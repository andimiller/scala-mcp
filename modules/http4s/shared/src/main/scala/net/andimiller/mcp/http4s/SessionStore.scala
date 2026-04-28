package net.andimiller.mcp.http4s

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*

/**
 * Pluggable session registry for MCP HTTP sessions.
 *
 * In-memory by default; can be replaced with an external store (e.g. Redis)
 * for distributed deployments.
 */
trait SessionStore[F[_]]:
  def put(session: McpSession[F]): F[McpSession[F]]
  def get(id: String): F[Option[McpSession[F]]]
  def remove(id: String): F[Unit]

object SessionStore:

  def inMemory[F[_]: Async]: F[SessionStore[F]] =
    Ref.of[F, Map[String, McpSession[F]]](Map.empty).map { ref =>
      new SessionStore[F]:
        def put(session: McpSession[F]): F[McpSession[F]] =
          ref.update(_ + (session.id -> session)).as(session)
        def get(id: String): F[Option[McpSession[F]]] =
          ref.get.map(_.get(id))
        def remove(id: String): F[Unit] =
          ref.update(_ - id)
    }

/**
 * Session store with user identity tracking for authenticated sessions.
 */
trait AuthenticatedSessionStore[F[_], U] extends SessionStore[F]:
  def putUser(sessionId: String, user: U): F[Unit]
  def getUser(sessionId: String): F[Option[U]]
  def removeUser(sessionId: String): F[Unit]

object AuthenticatedSessionStore:

  def inMemory[F[_]: Async, U]: F[AuthenticatedSessionStore[F, U]] =
    (
      Ref.of[F, Map[String, McpSession[F]]](Map.empty),
      Ref.of[F, Map[String, U]](Map.empty)
    ).mapN { (sessionRef, userRef) =>
      new AuthenticatedSessionStore[F, U]:
        def put(session: McpSession[F]): F[McpSession[F]] =
          sessionRef.update(_ + (session.id -> session)).as(session)
        def get(id: String): F[Option[McpSession[F]]] =
          sessionRef.get.map(_.get(id))
        def remove(id: String): F[Unit] =
          sessionRef.update(_ - id) *> userRef.update(_ - id)
        def putUser(sessionId: String, user: U): F[Unit] =
          userRef.update(_ + (sessionId -> user))
        def getUser(sessionId: String): F[Option[U]] =
          userRef.get.map(_.get(sessionId))
        def removeUser(sessionId: String): F[Unit] =
          userRef.update(_ - sessionId)
    }

/**
 * Factory that defers [[SessionStore]] creation until a `reconstruct`
 * callback is available.  This breaks the chicken-and-egg problem where
 * the store needs `reconstruct` but the builder owns the session-creation
 * logic.
 */
trait SessionStoreFactory[F[_]]:
  def create(reconstruct: String => F[McpSession[F]]): F[SessionStore[F]]
