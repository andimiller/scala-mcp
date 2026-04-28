package net.andimiller.mcp.core.protocol

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

class CancellationSuite extends FunSuite:

  test("CancelledNotificationParams round-trips with String requestId") {
    val p = CancelledNotificationParams(RequestId.fromString("abc"), Some("user-cancel"))
    p.asJson.as[CancelledNotificationParams].fold(err => fail(s"decode: $err"), identity) match
      case decoded =>
        assertEquals(decoded.requestId.asString, Some("abc"))
        assertEquals(decoded.reason, Some("user-cancel"))
  }

  test("CancelledNotificationParams round-trips with Long requestId") {
    val p = CancelledNotificationParams(RequestId.fromLong(5L), None)
    p.asJson.as[CancelledNotificationParams].fold(err => fail(s"decode: $err"), identity) match
      case decoded =>
        assertEquals(decoded.requestId.asLong, Some(5L))
        assertEquals(decoded.reason, None)
  }

  test("CancelledNotificationParams decodes when reason is absent") {
    val js = parse("""{"requestId":1}""").toOption.get
    js.as[CancelledNotificationParams].fold(err => fail(s"decode: $err"), identity) match
      case decoded =>
        assertEquals(decoded.requestId.asLong, Some(1L))
        assertEquals(decoded.reason, None)
  }
