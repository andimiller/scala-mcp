package net.andimiller.mcp.tapir

import net.andimiller.mcp.core.schema.JsonSchema
import sttp.tapir.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

given [A](using s: Schema[A]): JsonSchema[A] with
  def schema: sttp.apispec.Schema =
    TapirSchemaToJsonSchema(s, markOptionsAsNullable = false)
