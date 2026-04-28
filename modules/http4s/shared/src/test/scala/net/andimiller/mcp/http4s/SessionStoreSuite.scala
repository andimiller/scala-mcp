package net.andimiller.mcp.http4s

import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite
import net.andimiller.mcp.core.server.ClientChannel
import net.andimiller.mcp.core.server.RequestHandler
import net.andimiller.mcp.core.server.ServerRequester
import net.andimiller.mcp.core.server.CancellationRegistry
import net.andimiller.mcp.core.server.DefaultServer
import net.andimiller.mcp.core.protocol.Implementation
import net.andimiller.mcp.core.protocol.ServerCapabilities

class SessionStoreSuite extends CatsEffectSuite:

  private def fakeSession(id: String): IO[McpSession[IO]] =
    for
      cc        <- ClientChannel.noop[IO]
      server    <- DefaultServer[IO](Implementation("t", "0"), ServerCapabilities())
      cancel    <- CancellationRegistry.create[IO]
      requester <- ServerRequester.noop[IO]
      handler    = new RequestHandler[IO](server, requester, cancel)
      subs      <- Ref.of[IO, Set[String]](Set.empty)
    yield McpSession(id, handler, cc, subs)

  test("SessionStore.inMemory: put / get / remove round-trip") {
    for
      store <- SessionStore.inMemory[IO]
      s     <- fakeSession("a")
      _     <- store.put(s)
      got   <- store.get("a")
      _     <- store.remove("a")
      gone  <- store.get("a")
    yield
      assertEquals(got.map(_.id), Some("a"))
      assertEquals(gone, None)
  }

  test("SessionStore.inMemory: get returns None for unknown id") {
    for
      store <- SessionStore.inMemory[IO]
      got   <- store.get("missing")
    yield assertEquals(got, None)
  }

  test("AuthenticatedSessionStore: putUser / getUser / removeUser round-trip") {
    for
      store <- AuthenticatedSessionStore.inMemory[IO, String]
      s     <- fakeSession("a")
      _     <- store.put(s)
      _     <- store.putUser("a", "alice")
      user  <- store.getUser("a")
      _     <- store.removeUser("a")
      gone  <- store.getUser("a")
    yield
      assertEquals(user, Some("alice"))
      assertEquals(gone, None)
  }

  test("AuthenticatedSessionStore: remove(sid) clears both session and user") {
    for
      store <- AuthenticatedSessionStore.inMemory[IO, String]
      s     <- fakeSession("a")
      _     <- store.put(s)
      _     <- store.putUser("a", "alice")
      _     <- store.remove("a")
      sess  <- store.get("a")
      user  <- store.getUser("a")
    yield
      assertEquals(sess, None)
      assertEquals(user, None)
  }
