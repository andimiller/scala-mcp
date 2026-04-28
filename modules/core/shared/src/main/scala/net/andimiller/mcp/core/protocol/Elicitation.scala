package net.andimiller.mcp.core.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError

/** Server→client `elicitation/create` request envelope (form mode). */
case class ElicitationCreateRequest(
    message: String,
    requestedSchema: Json
) derives Encoder.AsObject,
      Decoder

/** Action chosen by the user in response to an elicitation request. Encoded as a bare string per the MCP spec ("accept"
  * / "decline" / "cancel").
  */
enum ElicitAction:

  case accept, decline, cancel

object ElicitAction:

  given Encoder[ElicitAction] = Encoder[String].contramap(_.toString)

  given Decoder[ElicitAction] = Decoder[String].emap {
    case "accept"  => Right(ElicitAction.accept)
    case "decline" => Right(ElicitAction.decline)
    case "cancel"  => Right(ElicitAction.cancel)
    case other     => Left(s"Unknown elicitation action: $other")
  }

/** Raw `elicitation/create` response from the client. */
case class ElicitationCreateResponse(
    action: ElicitAction,
    content: Option[Json] = None
) derives Encoder.AsObject,
      Decoder

/** Decoded elicitation result, parameterised by the user's submitted shape. */
enum ElicitResult[+A]:

  case Accept(value: A) extends ElicitResult[A]

  case Decline extends ElicitResult[Nothing]

  case Cancel extends ElicitResult[Nothing]

/** Failure modes when issuing an elicitation request. */
enum ElicitationError:

  case CapabilityMissing

  case Rpc(err: JsonRpcError)

  case Decode(message: String)

  case Timeout

  case Transport(message: String)
