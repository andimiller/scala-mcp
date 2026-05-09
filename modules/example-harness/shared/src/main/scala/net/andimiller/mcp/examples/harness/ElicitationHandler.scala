package net.andimiller.mcp.examples.harness

import java.nio.charset.StandardCharsets

import cats.effect.kernel.Async
import cats.effect.std.Console
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.ElicitAction
import net.andimiller.mcp.core.protocol.ElicitationCreateRequest
import net.andimiller.mcp.core.protocol.ElicitationCreateResponse
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import io.circe.Json
import io.circe.syntax.*

/** Handles `elicitation/create` — the server asks the *user* (via us) for input matching a JSON schema. We render the
  * schema's `properties` as a sequence of prompts and assemble the answers into an object the server can decode.
  *
  * Inputs `:cancel` cancels the request; an empty line on a non-required field skips it; EOF declines. Type coercion is
  * best-effort: `string` passthrough, `integer`/`number` via `toLongOption` / `toDoubleOption`, `boolean` via
  * `y/n/true/false/1/0`. Unknown types fall back to string.
  */
object ElicitationHandler:

  def handle[F[_]: Async: Console](
      id: RequestId,
      params: Option[Json]
  ): F[Either[JsonRpcError, Json]] =
    // Use stderr for handler-visibility messages: stdout is line-buffered so a `print`-only prompt
    // gets stuck behind the buffer until readLine returns, which it never does because the user
    // hasn't seen anything to respond to. stderr is unbuffered and shows immediately.
    Console[F].errorln(Theme.info("elicitation handler invoked")) *> {
      val parsed = params
        .toRight(JsonRpcError.invalidParams("missing params"))
        .flatMap(p =>
          p.as[ElicitationCreateRequest]
            .leftMap(e => JsonRpcError.invalidParams(s"elicitation/create: ${e.getMessage}"))
        )

      parsed match
        case Left(err) =>
          Console[F].errorln(Theme.err(s"elicitation: bad params — ${err.message}")).as(Left(err))
        case Right(req) =>
          for
            _      <- Console[F].errorln(Theme.info(s"elicitation: ${req.message}"))
            result <- promptSchema[F](req.requestedSchema)
          yield result match
            case None =>
              Right(ElicitationCreateResponse(ElicitAction.cancel, None).asJson.deepDropNullValues)
            case Some(content) =>
              Right(ElicitationCreateResponse(ElicitAction.accept, Some(content)).asJson.deepDropNullValues)
    }

  /** Walk `requestedSchema.properties` and collect one value per field. Returns `None` if the user cancels. */
  private def promptSchema[F[_]: Async: Console](schema: Json): F[Option[Json]] =
    val cursor   = schema.hcursor
    val required = cursor.downField("required").as[List[String]].getOrElse(Nil).toSet
    val props    = cursor.downField("properties")
    val fields   = props.keys.getOrElse(Iterable.empty).toList
    if fields.isEmpty then
      // Schema has no fields — accept with empty object so the server knows the user agreed.
      Async[F].pure(Some(Json.obj()))
    else
      fields
        .foldLeftM[F, Option[Map[String, Json]]](Some(Map.empty)) {
          case (None, _)        => Async[F].pure(None)
          case (Some(acc), key) =>
            val field      = props.downField(key)
            val ftype      = field.downField("type").as[String].getOrElse("string")
            val desc       = field.downField("description").as[String].toOption
            val isRequired = required(key)
            promptField[F](key, ftype, desc, isRequired).map {
              case PromptResult.Cancelled      => None
              case PromptResult.Skipped        => Some(acc)
              case PromptResult.Value(jsonVal) => Some(acc + (key -> jsonVal))
            }
        }
        .map(_.map(m => Json.obj(m.toSeq*)))

  private enum PromptResult:

    case Cancelled

    case Skipped

    case Value(json: Json)

  private def promptField[F[_]: Async: Console](
      key: String,
      ftype: String,
      desc: Option[String],
      required: Boolean
  ): F[PromptResult] =
    val typeHint = if required then s"$ftype, required" else s"$ftype, optional"
    val descLine = desc.fold("")(d => s" — $d")
    // Use println (with a trailing newline) instead of print: stdout is line-buffered so a print
    // without newline never reaches the terminal before readLine starts blocking. The user types
    // their answer on the line below the prompt, which is the standard CLI convention.
    val prompt                      = Theme.elicitPrompt(s"  $key ($typeHint)$descLine:")
    val readOnce: F[Option[String]] =
      Console[F].println(prompt) *>
        Console[F]
          .readLineWithCharset(StandardCharsets.UTF_8)
          .attempt
          .map(_.toOption.flatMap(Option(_)))
    readOnce.flatMap {
      case None       => Async[F].pure(PromptResult.Cancelled)
      case Some(line) =>
        val trimmed = line.trim
        if trimmed === ":cancel" then Async[F].pure(PromptResult.Cancelled)
        else if trimmed.isEmpty && !required then Async[F].pure(PromptResult.Skipped)
        else
          coerce(trimmed, ftype) match
            case Right(j)  => Async[F].pure(PromptResult.Value(j))
            case Left(msg) =>
              Console[F].errorln(Theme.err(s"$msg — try again or :cancel")) *>
                promptField(key, ftype, desc, required)
    }

  private def coerce(input: String, ftype: String): Either[String, Json] = ftype match
    case "string"  => Right(Json.fromString(input))
    case "integer" =>
      input.toLongOption.toRight(s"'$input' is not a valid integer").map(Json.fromLong)
    case "number" =>
      input.toDoubleOption
        .flatMap(Json.fromDouble)
        .toRight(s"'$input' is not a valid number")
    case "boolean" =>
      input.toLowerCase match
        case "y" | "yes" | "true" | "1" => Right(Json.True)
        case "n" | "no" | "false" | "0" => Right(Json.False)
        case _                          => Left(s"'$input' is not a boolean (try y/n/true/false)")
    case _ => Right(Json.fromString(input))
