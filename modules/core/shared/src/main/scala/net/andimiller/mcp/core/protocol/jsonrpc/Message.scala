package net.andimiller.mcp.core.protocol.jsonrpc

import io.circe.Json

/** Opaque type for JSON-RPC request IDs supporting String | Long */
opaque type RequestId = String | Long

object RequestId:
  def apply(value: String | Long): RequestId = value

  def fromString(s: String): RequestId = s
  def fromLong(l: Long): RequestId = l

  extension (id: RequestId)
    def value: String | Long = id
    def asString: Option[String] = id match
      case s: String => Some(s)
      case _ => None
    def asLong: Option[Long] = id match
      case l: Long => Some(l)
      case _ => None

/** JSON-RPC 2.0 error codes */
object ErrorCode:
  val ParseError: Int = -32700
  val InvalidRequest: Int = -32600
  val MethodNotFound: Int = -32601
  val InvalidParams: Int = -32602
  val InternalError: Int = -32603

/** JSON-RPC 2.0 error object */
case class JsonRpcError(
  code: Int,
  message: String,
  data: Option[Json] = None
)

object JsonRpcError:
  def parseError(message: String = "Parse error"): JsonRpcError =
    JsonRpcError(ErrorCode.ParseError, message)

  def invalidRequest(message: String = "Invalid request"): JsonRpcError =
    JsonRpcError(ErrorCode.InvalidRequest, message)

  def methodNotFound(method: String): JsonRpcError =
    JsonRpcError(ErrorCode.MethodNotFound, s"Method not found: $method")

  def invalidParams(message: String = "Invalid params"): JsonRpcError =
    JsonRpcError(ErrorCode.InvalidParams, message)

  def internalError(message: String = "Internal error"): JsonRpcError =
    JsonRpcError(ErrorCode.InternalError, message)

/** JSON-RPC 2.0 message types */
enum Message:
  case Request(
    jsonrpc: String,
    id: RequestId,
    method: String,
    params: Option[Json]
  )

  case Response(
    jsonrpc: String,
    id: RequestId,
    result: Option[Json],
    error: Option[JsonRpcError]
  )

  case Notification(
    jsonrpc: String,
    method: String,
    params: Option[Json]
  )

object Message:
  def request(id: RequestId, method: String, params: Option[Json] = None): Message =
    Request("2.0", id, method, params)

  def response(id: RequestId, result: Json): Message =
    Response("2.0", id, Some(result), None)

  def errorResponse(id: RequestId, error: JsonRpcError): Message =
    Response("2.0", id, None, Some(error))

  def notification(method: String, params: Option[Json] = None): Message =
    Notification("2.0", method, params)
