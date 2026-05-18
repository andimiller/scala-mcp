package net.andimiller.mcp.core.protocol

import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.syntax.*

/** Whether a tool participates in task-augmented (long-running, pollable) execution. */
enum TaskSupport:

  /** Tool never runs as a task. (Default per spec.) */
  case Forbidden

  /** Tool may run as a task if the client requests it. */
  case Optional

  /** Tool always runs as a task. */
  case Required

object TaskSupport:

  given Encoder[TaskSupport] = Encoder.instance:
    case Forbidden => "forbidden".asJson
    case Optional  => "optional".asJson
    case Required  => "required".asJson

  given Decoder[TaskSupport] = Decoder.instance: cursor =>
    cursor
      .as[String]
      .flatMap:
        case "forbidden" => Right(Forbidden)
        case "optional"  => Right(Optional)
        case "required"  => Right(Required)
        case other       => Left(DecodingFailure(s"Unknown taskSupport: $other", cursor.history))

/** Per-tool execution hints. Currently the only field is `taskSupport`. */
case class ToolExecution(
    taskSupport: TaskSupport = TaskSupport.Forbidden
) derives Encoder.AsObject,
      Decoder

object ToolExecution:

  val taskForbidden: ToolExecution = ToolExecution(TaskSupport.Forbidden)

  val taskOptional: ToolExecution = ToolExecution(TaskSupport.Optional)

  val taskRequired: ToolExecution = ToolExecution(TaskSupport.Required)
