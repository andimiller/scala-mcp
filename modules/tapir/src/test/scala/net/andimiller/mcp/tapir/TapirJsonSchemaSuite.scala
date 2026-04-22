package net.andimiller.mcp.tapir

import io.circe.syntax.*
import net.andimiller.mcp.core.schema.JsonSchema
import sttp.apispec.circe.given
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

class TapirJsonSchemaSuite extends munit.FunSuite:
  import TapirJsonSchemaSuite.*

  test("case class is translated to an object schema with properties") {
    val json = JsonSchema[Simple].schema.asJson
    assert(
      json.hcursor.downField("properties").succeeded,
      s"Expected properties object, got: $json"
    )
  }

  test("@description on a field surfaces in the JSON schema") {
    val json = JsonSchema[WithDescription].schema.asJson
    val desc = json.hcursor
      .downField("properties").downField("name")
      .downField("description").as[String].toOption
    assertEquals(desc, Some("the name"))
  }

  test("Option fields are not in required (markOptionsAsNullable = false)") {
    val json = JsonSchema[WithOption].schema.asJson
    val required = json.hcursor.downField("required").as[List[String]].toOption
    assertEquals(required, Some(List("a")))
  }

  test("nested case class produces a $ref into $defs") {
    val json = JsonSchema[Outer].schema.asJson
    val ref = json.hcursor
      .downField("properties").downField("inner")
      .downField("$ref").as[String].toOption
    assertEquals(ref, Some("#/$defs/Inner"))
    val innerXType = json.hcursor
      .downField("$defs").downField("Inner")
      .downField("properties").downField("x")
      .downField("type").as[String].toOption
    assertEquals(innerXType, Some("integer"))
  }

  test("List[A] fields produce an array schema with items") {
    val json = JsonSchema[WithList].schema.asJson
    val items = json.hcursor
      .downField("properties").downField("names")
      .downField("items")
    assert(items.succeeded, s"Expected items schema, got: $json")
  }

object TapirJsonSchemaSuite:
  final case class Simple(name: String, age: Int) derives Schema
  final case class WithDescription(@description("the name") name: String) derives Schema
  final case class WithOption(a: String, b: Option[Int]) derives Schema
  final case class Inner(x: Int) derives Schema
  final case class Outer(inner: Inner) derives Schema
  final case class WithList(names: List[String]) derives Schema
