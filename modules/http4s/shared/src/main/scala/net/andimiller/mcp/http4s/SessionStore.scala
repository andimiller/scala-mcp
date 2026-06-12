package net.andimiller.mcp.http4s

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.syntax.all.*

import io.circe.Decoder
import io.circe.Encoder

/** Pluggable session registry for MCP HTTP sessions.
  *
  * In-memory by default; can be replaced with an external store (e.g. Redis) for distributed deployments.
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

/** Session store with user identity tracking for authenticated sessions. */
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

/** Factory that defers [[SessionStore]] creation until a `reconstruct` callback is available. This breaks the
  * chicken-and-egg problem where the store needs `reconstruct` but the builder owns the session-creation logic.
  */
trait SessionStoreFactory[F[_]]:

  def create(reconstruct: String => F[McpSession[F]]): F[SessionStore[F]]

/** Factory that defers [[AuthenticatedSessionStore]] creation until both a `reconstruct` callback and the user-type
  * codec evidence are available. The user-type codecs are required at construction time (e.g. for serialising user
  * identity into Redis), but they only become available inside `.authenticated[U]`, so this factory takes them as a
  * type parameter at call time rather than at factory construction.
  *
  * The `reconstruct` callback is user-aware so cache-miss session rebuilds on a different process can recover the
  * original authenticated identity from the store and materialise a `Server[F]` with the same tool-visibility
  * predicates as the original session. Implementations are expected to fetch the user (e.g. via `getUser`) before
  * invoking `reconstruct`.
  */
trait AuthenticatedSessionStoreFactory[F[_]]:

  def createAuthenticated[U: Encoder: Decoder](
      reconstruct: (String, U) => F[McpSession[F]]
  ): F[AuthenticatedSessionStore[F, U]]
