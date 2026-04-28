package net.andimiller.mcp.openapi

import munit.FunSuite
import scala.collection.immutable.ListMap
import sttp.apispec.Schema
import sttp.apispec.SchemaType
import sttp.apispec.SchemaLike
import io.circe.Json

class SchemaConverterSuite extends FunSuite:

  test("resolveSchemaLike resolves a simple $ref") {
    val petSchema = Schema(
      `type` = Some(List(SchemaType.Object)),
      properties = ListMap("name" -> Schema(`type` = Some(List(SchemaType.String))))
    )
    val refSchema  = Schema($ref = Some("#/components/schemas/Pet"))
    val components = ListMap[String, SchemaLike]("Pet" -> petSchema)

    val resolved = SchemaConverter.resolveSchemaLike(refSchema, components)
    assertEquals(resolved, petSchema)
  }

  test("resolveSchemaLike resolves nested refs") {
    val innerSchema  = Schema(`type` = Some(List(SchemaType.String)))
    val middleSchema = Schema($ref = Some("#/components/schemas/Inner"))
    val outerSchema  = Schema($ref = Some("#/components/schemas/Middle"))
    val components   = ListMap[String, SchemaLike](
      "Inner"  -> innerSchema,
      "Middle" -> middleSchema
    )

    val resolved = SchemaConverter.resolveSchemaLike(outerSchema, components)
    assertEquals(resolved, innerSchema)
  }

  test("resolveSchemaLike handles missing ref gracefully") {
    val refSchema  = Schema($ref = Some("#/components/schemas/DoesNotExist"))
    val components = ListMap.empty[String, SchemaLike]

    val resolved = SchemaConverter.resolveSchemaLike(refSchema, components)
    assertEquals(resolved, refSchema)
  }

  test("wrapIfArray wraps array schemas") {
    val arraySchema =
      Json.obj("type" -> Json.fromString("array"), "items" -> Json.obj("type" -> Json.fromString("string")))
    val wrapped = SchemaConverter.wrapIfArray(arraySchema)
    val typ     = wrapped.hcursor.downField("type").as[String].getOrElse("")
    assertEquals(typ, "object")
  }

  test("wrapIfArray leaves object schemas unchanged") {
    val objectSchema = Json.obj("type" -> Json.fromString("object"))
    val result       = SchemaConverter.wrapIfArray(objectSchema)
    assertEquals(result, objectSchema)
  }
