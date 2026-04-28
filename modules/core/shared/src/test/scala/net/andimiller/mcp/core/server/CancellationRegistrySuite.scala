package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.kernel.Deferred
import munit.CatsEffectSuite
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import scala.concurrent.duration.*

class CancellationRegistrySuite extends CatsEffectSuite:

  private val id1 = RequestId.fromLong(1L)

  private val id2 = RequestId.fromLong(2L)

  test("track returns Some(value) on success") {
    for
      reg    <- CancellationRegistry.create[IO]
      result <- reg.track(id1)(IO.pure(42))
    yield assertEquals(result, Some(42))
  }

  test("track returns None when cancel fires mid-work") {
    for
      reg    <- CancellationRegistry.create[IO]
      gate   <- Deferred[IO, Unit]
      fib    <- reg.track(id1)(gate.get).start
      _      <- IO.sleep(50.millis) // ensure track has registered before we cancel
      _      <- reg.cancel(id1)
      result <- fib.joinWithNever
    yield assertEquals(result, None)
  }

  test("cancel for an unknown id is a no-op") {
    for
      reg <- CancellationRegistry.create[IO]
      _   <- reg.cancel(id1)
    yield ()
  }

  test("cancel after track completed is a no-op") {
    for
      reg <- CancellationRegistry.create[IO]
      _   <- reg.track(id1)(IO.pure(1))
      _   <- reg.cancel(id1)
    yield ()
  }

  test("registry is cleaned up after success and after cancellation") {
    for
      reg  <- CancellationRegistry.create[IO]
      _    <- reg.track(id1)(IO.pure(1))
      gate <- Deferred[IO, Unit]
      fib  <- reg.track(id2)(gate.get).start
      _    <- IO.sleep(50.millis)
      _    <- reg.cancel(id2)
      _    <- fib.joinWithNever
      _    <- reg.cancel(id1)
      _    <- reg.cancel(id2)
    yield ()
  }

  test("track work raising → exception propagates and registry is cleaned up") {
    val boom = new RuntimeException("boom")
    for
      reg    <- CancellationRegistry.create[IO]
      caught <- reg.track(id1)(IO.raiseError[Int](boom)).attempt
      _      <- reg.cancel(id1)
    yield caught match
      case Left(t)  => assertEquals(t.getMessage, "boom")
      case Right(_) => fail("expected error to propagate")
  }

  test("two concurrent tracks: cancelling one does not affect the other") {
    for
      reg   <- CancellationRegistry.create[IO]
      gate1 <- Deferred[IO, Unit]
      gate2 <- Deferred[IO, Unit]
      fib1  <- reg.track(id1)(gate1.get.as("one")).start
      fib2  <- reg.track(id2)(gate2.get.as("two")).start
      _     <- IO.sleep(50.millis)
      _     <- reg.cancel(id1)
      r1    <- fib1.joinWithNever
      _     <- gate2.complete(())
      r2    <- fib2.joinWithNever
    yield
      assertEquals(r1, None)
      assertEquals(r2, Some("two"))
  }

  test("cancelAll resolves every in-flight track to None") {
    for
      reg   <- CancellationRegistry.create[IO]
      gate1 <- Deferred[IO, Unit]
      gate2 <- Deferred[IO, Unit]
      fib1  <- reg.track(id1)(gate1.get).start
      fib2  <- reg.track(id2)(gate2.get).start
      _     <- IO.sleep(50.millis)
      _     <- reg.cancelAll
      r1    <- fib1.joinWithNever
      r2    <- fib2.joinWithNever
    yield
      assertEquals(r1, None)
      assertEquals(r2, None)
  }
