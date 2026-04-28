package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.all.*
import io.circe.Codec
import io.circe.Json
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.schema.JsonSchema

import scala.concurrent.duration.FiniteDuration

/** Server-side API for issuing `elicitation/create` requests to the client.
  *
  * Form mode only — the server sends a JSON Schema describing the data it wants from the user; the client renders a
  * form, the user submits, and the response is decoded back to `A`.
  *
  * The schema is derived from `JsonSchema[A]` (existing project typeclass) and the response is decoded via
  * `io.circe.Codec[A]`.
  */
trait ElicitationClient[F[_]]:

  /** Ask the user to fill out a form whose shape is described by `JsonSchema[A]`.
    *
    * @param message
    *   human-readable prompt shown alongside the form
    * @param timeout
    *   optional bound on how long to wait for the user to respond
    */
  def requestForm[A: Codec: JsonSchema](
      message: String,
      timeout: Option[FiniteDuration] = None
  ): F[Either[ElicitationError, ElicitResult[A]]]

object ElicitationClient:

  /** Build an [[ElicitationClient]] backed by a [[ServerRequester]]. */
  def fromRequester[F[_]: Async](requester: ServerRequester[F]): ElicitationClient[F] =
    new ElicitationClient[F]:
      def requestForm[A](message: String, timeout: Option[FiniteDuration])(using
          codec: Codec[A],
          schema: JsonSchema[A]
      ): F[Either[ElicitationError, ElicitResult[A]]] =
        requester.clientCapabilities.flatMap { caps =>
          // The 2025-11-25 spec adds a `form` sub-capability under `elicitation`,
          // but most clients (including many current ones) advertise just
          // `elicitation: {}` to signal form-mode support. Treat presence of the
          // `elicitation` field as enough.
          val supported = caps.flatMap(_.elicitation).isDefined
          if !supported then
            (Left(ElicitationError.CapabilityMissing): Either[ElicitationError, ElicitResult[A]]).pure[F]
          else
            val params = ElicitationCreateRequest(message, JsonSchema.toJson[A]).asJson
            requester.request("elicitation/create", Some(params), timeout).map {
              case Left(err) =>
                if err.code == ServerRequester.TimeoutErrorCode then Left(ElicitationError.Timeout)
                else Left(ElicitationError.Rpc(err))
              case Right(json) =>
                json.as[ElicitationCreateResponse] match
                  case Left(decodeFailure) =>
                    Left(ElicitationError.Decode(decodeFailure.getMessage))
                  case Right(envelope) => decodeEnvelope[A](envelope)
            }
        }

  private def decodeEnvelope[A](envelope: ElicitationCreateResponse)(using
      codec: Codec[A]
  ): Either[ElicitationError, ElicitResult[A]] =
    envelope.action match
      case ElicitAction.decline => Right(ElicitResult.Decline)
      case ElicitAction.cancel  => Right(ElicitResult.Cancel)
      case ElicitAction.accept  =>
        envelope.content match
          case None =>
            Left(ElicitationError.Decode("accept response missing 'content' field"))
          case Some(json) =>
            json.as[A] match
              case Right(value) => Right(ElicitResult.Accept(value))
              case Left(err)    => Left(ElicitationError.Decode(err.getMessage))
