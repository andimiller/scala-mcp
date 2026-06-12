package net.andimiller.mcp.redis

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*

import net.andimiller.mcp.core.server.ClientChannel
import net.andimiller.mcp.core.server.RequestHandler
import net.andimiller.mcp.http4s.McpSession

import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.NoOp.given
import io.circe.Decoder
import io.circe.Encoder
import munit.CatsEffectSuite
import org.testcontainers.containers.wait.strategy.Wait

/** Cross-process Redis integration test for [[RedisAuthenticatedSessionStore]].
  *
  * Verifies the multi-instance contract that the silent-fallback bug used to break: a session created and bound to a
  * user on instance A is recoverable on instance B (cold local cache, same Redis), and the cache-miss reconstruct on B
  * receives the user identity stored by A so the rebuilt `Server[F]` can apply tool-visibility predicates.
  */
class RedisAuthenticatedSessionStoreSpec extends CatsEffectSuite with TestContainerForAll:

  case class User(name: String, isAdmin: Boolean) derives Encoder.AsObject, Decoder

  override val containerDef: GenericContainer.Def[GenericContainer] = GenericContainer.Def(
    dockerImage = "redis:7-alpine",
    exposedPorts = Seq(6379),
    waitStrategy = Wait.forListeningPort()
  )

  private def redisUri(container: GenericContainer): String =
    s"redis://${container.host}:${container.mappedPort(6379)}"

  /** Build an `McpSession` whose handler/clientChannel/etc. are real-enough to satisfy `RedisSessionStore.put` (which
    * only writes the id to Redis and stashes the object in its local cache). The handler is never invoked in this test;
    * we're verifying the cross-instance store contract, not request dispatch.
    */
  private def stubSession(id: String): IO[McpSession[IO]] =
    for
      cc            <- ClientChannel.noop[IO]
      subscriptions <- Ref.of[IO, Set[String]](Set.empty)
    yield McpSession(id, null.asInstanceOf[RequestHandler[IO]], cc, subscriptions)

  test("session + user written on instance A are visible from instance B via cache-miss reconstruct") {
    withContainers { case container: GenericContainer =>
      Redis[IO].utf8(redisUri(container)).use { redis =>
        for
          // Two stores share the Redis client but have independent local caches — simulating two processes.
          cacheA      <- Ref.of[IO, Map[String, McpSession[IO]]](Map.empty)
          cacheB      <- Ref.of[IO, Map[String, McpSession[IO]]](Map.empty)
          reconstructA = (id: String, u: User) => stubSession(id) // unused on A
          // B's reconstruct captures the user it was handed so we can assert against it.
          observedOnB <- Ref.of[IO, Option[(String, User)]](None)
          reconstructB = (id: String, u: User) => observedOnB.set(Some((id, u))) *> stubSession(id)
          storeA       = new RedisAuthenticatedSessionStore[IO, User](redis, cacheA, reconstructA, 1.minute)
          storeB       = new RedisAuthenticatedSessionStore[IO, User](redis, cacheB, reconstructB, 1.minute)
          sessionId    = "sess-multi-instance"
          alice        = User("alice", isAdmin = true)
          // ── Instance A: create the session, bind the user ──
          session <- stubSession(sessionId)
          _       <- storeA.put(session)
          _       <- storeA.putUser(sessionId, alice)
          // ── Instance B: cold cache, must reconstruct ─────
          recoveredB <- storeB.get(sessionId)
          userOnB    <- storeB.getUser(sessionId)
          observed   <- observedOnB.get
        yield
          assert(recoveredB.isDefined, "instance B must be able to find the session")
          assertEquals(userOnB, Some(alice), "instance B must read alice from Redis")
          assertEquals(observed, Some((sessionId, alice)), "B's reconstruct must run with the stored user")
      }
    }
  }

  test("removeUser on one instance is visible to the other") {
    withContainers { case container: GenericContainer =>
      Redis[IO].utf8(redisUri(container)).use { redis =>
        for
          cacheA  <- Ref.of[IO, Map[String, McpSession[IO]]](Map.empty)
          cacheB  <- Ref.of[IO, Map[String, McpSession[IO]]](Map.empty)
          stub     = (id: String, _: User) => stubSession(id)
          storeA   = new RedisAuthenticatedSessionStore[IO, User](redis, cacheA, stub, 1.minute)
          storeB   = new RedisAuthenticatedSessionStore[IO, User](redis, cacheB, stub, 1.minute)
          sid      = "sess-remove-user"
          bob      = User("bob", isAdmin = false)
          session <- stubSession(sid)
          _       <- storeA.put(session)
          _       <- storeA.putUser(sid, bob)
          _       <- storeB.removeUser(sid)
          afterA  <- storeA.getUser(sid)
          afterB  <- storeB.getUser(sid)
        yield
          assertEquals(afterA, None, "user removal must be visible from A")
          assertEquals(afterB, None, "user removal must be visible from B")
      }
    }
  }

  test("missing user identity makes a cross-instance get return None (no reconstruct without user)") {
    // Defensive case: if the session marker is in Redis but `_user` was never set (or was deleted), `get` should
    // refuse to reconstruct rather than rebuild a session with the wrong identity.
    withContainers { case container: GenericContainer =>
      Redis[IO].utf8(redisUri(container)).use { redis =>
        for
          cacheA        <- Ref.of[IO, Map[String, McpSession[IO]]](Map.empty)
          cacheB        <- Ref.of[IO, Map[String, McpSession[IO]]](Map.empty)
          reconstructed <- Ref.of[IO, Boolean](false)
          recA           = (id: String, _: User) => stubSession(id)
          recB           = (id: String, _: User) => reconstructed.set(true) *> stubSession(id)
          storeA         = new RedisAuthenticatedSessionStore[IO, User](redis, cacheA, recA, 1.minute)
          storeB         = new RedisAuthenticatedSessionStore[IO, User](redis, cacheB, recB, 1.minute)
          sid            = "sess-no-user"
          session       <- stubSession(sid)
          _             <- storeA.put(session) // marker set in redis, no putUser
          recovered     <- storeB.get(sid)
          ran           <- reconstructed.get
        yield
          assertEquals(recovered, None, "get must return None when no user is bound")
          assertEquals(ran, false, "reconstruct must not run without a stored user")
      }
    }
  }
