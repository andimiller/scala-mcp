package net.andimiller.mcp.trace4cats

import cats.effect.IO
import cats.effect.IOLocal
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.CallToolRequest
import net.andimiller.mcp.core.protocol.CallToolResponse
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.server.Tool
import net.andimiller.mcp.core.server.ToolCallContext

import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import trace4cats.AttributeValue
import trace4cats.EntryPoint
import trace4cats.RefSpanCompleter
import trace4cats.Span
import trace4cats.SpanSampler
import trace4cats.SpanStatus
import trace4cats.context.Local
import trace4cats.iolocal.ioLocalProvide
import trace4cats.model.CompletedSpan

class EntrypointTraceToolMiddlewareSuite extends CatsEffectSuite:

  // A valid W3C traceparent: version 00, 32-hex traceId, 16-hex parent spanId, sampled flag.
  private val inboundTraceId = "0123456789abcdef0123456789abcdef"

  private val inboundSpanId = "fedcba9876543210"

  private val inboundTraceparent = s"00-$inboundTraceId-$inboundSpanId-01"

  /** Stand up `EntryPoint[IO]` + `Local[IO, Span[IO]]` and run the body. Returns the body's result plus the completed
    * spans collected by the in-memory `RefSpanCompleter`.
    */
  private def runMw[A](
      body: (EntryPoint[IO], Local[IO, Span[IO]]) => IO[A]
  ): IO[(A, List[CompletedSpan])] =
    for
      completer <- RefSpanCompleter[IO]("mcp-trace4cats-test")
      ep         = EntryPoint[IO](SpanSampler.always[IO], completer)
      rootSpan  <- ep.root("test-root").allocated.map(_._1) // sacrificial root to seed the IOLocal
      ioLocal   <- IOLocal[Span[IO]](rootSpan)
      local      = ioLocalProvide(ioLocal)
      result    <- body(ep, local)
      spans     <- completer.get
    yield (result, spans.toList)

  /** Build a stub `ToolCallContext` over a request. The propagation middleware doesn't read `resolved` itself, but
    * extractors can.
    */
  private def ctxOf(req: CallToolRequest, sessionId: Option[String] = None): ToolCallContext[IO, Unit] =
    val resolved = new Tool[IO, Unit]:
      def name                                                          = req.name
      def description                                                   = req.name
      def inputSchema                                                   = Json.obj()
      def outputSchema                                                  = None
      def handle(call: ToolCallContext[IO, Unit]): IO[CallToolResponse] = IO.pure(CallToolResponse(Nil))
    ToolCallContext(req, sessionId, resolved, ())

  private val okHandler: ToolCallContext[IO, Unit] => IO[CallToolResponse] =
    _ => IO.pure(CallToolResponse(List(Content.Text("ok"))))

  private val errHandler: ToolCallContext[IO, Unit] => IO[CallToolResponse] =
    _ => IO.pure(CallToolResponse(List(Content.Text("boom")), isError = true))

  test("inherits inbound traceId when request._meta carries a W3C traceparent") {
    val req = CallToolRequest(
      name = "echo",
      arguments = Json.obj(),
      _meta = Some(JsonObject("traceparent" -> inboundTraceparent.asJson))
    )

    runMw { (ep, local) =>
      val mw = EntrypointTraceToolMiddleware[IO, Unit](ep, local, TraceMetaExtractor.w3c[IO, Unit])
      mw(okHandler)(ctxOf(req))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call echo").getOrElse(fail("expected tool span"))
      assertEquals(toolSpan.context.traceId.show, inboundTraceId, s"got: ${toolSpan.context.traceId.show}")
      // parent of the tool span is the inbound spanId, not the seeded test-root
      assertEquals(toolSpan.context.parent.map(_.spanId.show), Some(inboundSpanId))
    }
  }

  test("falls back to a fresh root traceId when no traceparent is present") {
    val req = CallToolRequest(name = "echo", arguments = Json.obj(), _meta = None)

    runMw { (ep, local) =>
      val mw = EntrypointTraceToolMiddleware[IO, Unit](ep, local, TraceMetaExtractor.w3c[IO, Unit])
      mw(okHandler)(ctxOf(req))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call echo").getOrElse(fail("expected tool span"))
      assert(toolSpan.context.traceId.show =!= inboundTraceId, "should have fresh traceId, not inbound")
      assertEquals(toolSpan.context.parent, None, "fresh root should have no parent")
    }
  }

  test("records mcp.tool.name + is_error=false + Ok status on success") {
    val req = CallToolRequest(name = "echo", arguments = Json.obj())

    runMw { (ep, local) =>
      val mw = EntrypointTraceToolMiddleware[IO, Unit](ep, local, TraceMetaExtractor.w3c[IO, Unit])
      mw(okHandler)(ctxOf(req))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call echo").getOrElse(fail("expected tool span"))
      assertEquals(toolSpan.attributes.get("mcp.tool.name"), Some(AttributeValue.StringValue("echo")))
      assertEquals(toolSpan.attributes.get("mcp.tool.is_error"), Some(AttributeValue.BooleanValue(false)))
      assertEquals(toolSpan.status, SpanStatus.Ok)
    }
  }

  test("records mcp.session.id when context carries a sessionId") {
    val req = CallToolRequest(name = "echo", arguments = Json.obj())

    runMw { (ep, local) =>
      val mw = EntrypointTraceToolMiddleware[IO, Unit](ep, local, TraceMetaExtractor.w3c[IO, Unit])
      mw(okHandler)(ctxOf(req, sessionId = Some("sess-xyz")))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call echo").getOrElse(fail("expected tool span"))
      assertEquals(toolSpan.attributes.get("mcp.session.id"), Some(AttributeValue.StringValue("sess-xyz")))
    }
  }

  test("records Internal status when response.isError = true") {
    val req = CallToolRequest(name = "oops", arguments = Json.obj())

    runMw { (ep, local) =>
      val mw = EntrypointTraceToolMiddleware[IO, Unit](ep, local, TraceMetaExtractor.w3c[IO, Unit])
      mw(errHandler)(ctxOf(req))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call oops").getOrElse(fail("expected tool span"))
      assertEquals(toolSpan.attributes.get("mcp.tool.is_error"), Some(AttributeValue.BooleanValue(true)))
      assert(toolSpan.status.isInstanceOf[SpanStatus.Internal], s"expected Internal, got: ${toolSpan.status}")
    }
  }

  test("extractor attributes are attached to the propagation span") {
    val extractor: ToolCallContext[IO, Unit] => IO[Map[String, AttributeValue]] = ctx =>
      val user = ctx.request.arguments.hcursor.get[String]("user").toOption
      IO.pure(user.map(u => Map("mcp.tool.user" -> AttributeValue.StringValue(u))).getOrElse(Map.empty))

    val req = CallToolRequest(name = "echo", arguments = Json.obj("user" -> "alice".asJson))

    runMw { (ep, local) =>
      val mw = EntrypointTraceToolMiddleware[IO, Unit](ep, local, TraceMetaExtractor.w3c[IO, Unit], extractor)
      mw(okHandler)(ctxOf(req))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call echo").getOrElse(fail("expected tool span"))
      assertEquals(toolSpan.attributes.get("mcp.tool.user"), Some(AttributeValue.StringValue("alice")))
    }
  }
