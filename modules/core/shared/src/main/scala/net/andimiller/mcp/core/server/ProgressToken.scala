package net.andimiller.mcp.core.server

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

/** Opaque type for MCP progress tokens supporting String | Long. Mirrors RequestId. */
opaque type ProgressToken = String | Long

object ProgressToken:
  def apply(value: String | Long): ProgressToken = value

  def fromString(s: String): ProgressToken = s
  def fromLong(l: Long): ProgressToken = l

  extension (token: ProgressToken)
    def value: String | Long = token
    def asString: Option[String] = token match
      case s: String => Some(s)
      case _         => None
    def asLong: Option[Long] = token match
      case l: Long => Some(l)
      case _       => None

  given Encoder[ProgressToken] = Encoder.instance { token =>
    token.value match
      case s: String => s.asJson
      case l: Long   => l.asJson
  }

  given Decoder[ProgressToken] = Decoder.instance { cursor =>
    cursor.as[String].map(ProgressToken.fromString)
      .orElse(cursor.as[Long].map(ProgressToken.fromLong))
  }
