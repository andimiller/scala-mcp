package net.andimiller.mcp.core.server

import cats.effect.IO
import munit.CatsEffectSuite
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

class CancellationRegistrySuite extends CatsEffectSuite:

  test("cancel on unknown id is a no-op") {
    CancellationRegistry.create[IO].flatMap { reg =>
      reg.cancel(RequestId.fromString("nope")).as(assert(true))
    }
  }

  test("register + cancel completes the deferred") {
    CancellationRegistry.create[IO].flatMap { reg =>
      val id = RequestId.fromString("req-1")
      for
        deferred <- reg.register(id)
        _        <- reg.cancel(id)
        completed <- deferred.tryGet.map(_.isDefined)
      yield assertEquals(completed, true)
    }
  }

  test("isActive reflects registration and completion") {
    CancellationRegistry.create[IO].flatMap { reg =>
      val id = RequestId.fromLong(42L)
      for
        before  <- reg.isActive(id)
        _       <- reg.register(id)
        during  <- reg.isActive(id)
        _       <- reg.complete(id)
        after   <- reg.isActive(id)
      yield
        assertEquals(before, false)
        assertEquals(during, true)
        assertEquals(after, false)
    }
  }

  test("double-cancel is safe") {
    CancellationRegistry.create[IO].flatMap { reg =>
      val id = RequestId.fromString("req-2")
      for
        _ <- reg.register(id)
        _ <- reg.cancel(id)
        _ <- reg.cancel(id) // second cancel should be a no-op, not fail
      yield assert(true)
    }
  }

  test("complete after cancel is safe") {
    CancellationRegistry.create[IO].flatMap { reg =>
      val id = RequestId.fromString("req-3")
      for
        _ <- reg.register(id)
        _ <- reg.cancel(id)
        _ <- reg.complete(id) // handler's guarantee should not error
      yield assert(true)
    }
  }

  test("string and long ids with same textual form are distinct") {
    CancellationRegistry.create[IO].flatMap { reg =>
      val stringId = RequestId.fromString("42")
      val longId   = RequestId.fromLong(42L)
      for
        _        <- reg.register(stringId)
        _        <- reg.register(longId)
        active1  <- reg.isActive(stringId)
        active2  <- reg.isActive(longId)
        _        <- reg.cancel(stringId)
        stillLong <- reg.isActive(longId)
      yield
        assertEquals(active1, true)
        assertEquals(active2, true)
        assertEquals(stillLong, true) // cancelling string id doesn't affect long id
    }
  }
