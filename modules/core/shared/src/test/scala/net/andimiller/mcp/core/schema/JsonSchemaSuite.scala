package net.andimiller.mcp.core.schema

import munit.FunSuite
import io.circe.Json

class JsonSchemaSuite extends FunSuite:

  case class NoAnnotations(name: String, age: Int) derives JsonSchema

  case class WithDescription(
    @description("The user's name") name: String,
    age: Int
  ) derives JsonSchema

  case class WithExamples(
    @example("1d6")
    @example("2d20 + 5")
    dice: String
  ) derives JsonSchema

  case class WithBoth(
    @description("Dice notation (e.g., '1d6', '2d20 + 5')")
    @example("1d6")
    @example("2d20 + 5")
    dice: String,
    verbose: Boolean
  ) derives JsonSchema

  test("fields without annotations produce clean JSON") {
    val json = JsonSchema.toJson[NoAnnotations]
    val nameObj = json.hcursor.downField("properties").downField("name").focus.get
    assertEquals(nameObj, Json.obj("type" -> Json.fromString("string")))
  }

  test("@description produces description in JSON") {
    val json = JsonSchema.toJson[WithDescription]
    val nameDesc = json.hcursor.downField("properties").downField("name").downField("description").as[String]
    assertEquals(nameDesc, Right("The user's name"))

    val ageObj = json.hcursor.downField("properties").downField("age").focus.get
    assertEquals(ageObj, Json.obj("type" -> Json.fromString("integer")))
  }

  test("@example produces examples array in JSON") {
    val json = JsonSchema.toJson[WithExamples]
    val examples = json.hcursor.downField("properties").downField("dice").downField("examples").as[List[String]]
    assertEquals(examples, Right(List("1d6", "2d20 + 5")))
  }

  test("@description and @example work together") {
    val json = JsonSchema.toJson[WithBoth]
    val dice = json.hcursor.downField("properties").downField("dice")

    val desc = dice.downField("description").as[String]
    assertEquals(desc, Right("Dice notation (e.g., '1d6', '2d20 + 5')"))

    val examples = dice.downField("examples").as[List[String]]
    assertEquals(examples, Right(List("1d6", "2d20 + 5")))

    // verbose field should have no description or examples
    val verboseObj = json.hcursor.downField("properties").downField("verbose").focus.get
    assertEquals(verboseObj, Json.obj("type" -> Json.fromString("boolean")))
  }

  case class WithIntExamples(
    @description("The user's age in years")
    @example(25)
    @example(30)
    age: Int
  ) derives JsonSchema

  test("@example with Int literals produces JSON numbers") {
    val json = JsonSchema.toJson[WithIntExamples]
    val age = json.hcursor.downField("properties").downField("age")
    assertEquals(age.downField("type").as[String], Right("integer"))
    assertEquals(age.downField("description").as[String], Right("The user's age in years"))
    val examples = age.downField("examples").focus.get
    assertEquals(examples, Json.arr(Json.fromInt(25), Json.fromInt(30)))
  }

  case class WithDoubleExamples(
    @description("Temperature in celsius")
    @example(36.6)
    @example(98.6)
    temp: Double
  ) derives JsonSchema

  test("@example with Double literals produces JSON numbers") {
    val json = JsonSchema.toJson[WithDoubleExamples]
    val temp = json.hcursor.downField("properties").downField("temp")
    assertEquals(temp.downField("type").as[String], Right("number"))
    assertEquals(temp.downField("description").as[String], Right("Temperature in celsius"))
    val examples = temp.downField("examples").focus.get
    assertEquals(examples, Json.arr(Json.fromDoubleOrNull(36.6), Json.fromDoubleOrNull(98.6)))
  }

  case class WithBooleanExamples(
    @description("Whether to enable verbose output")
    @example(true)
    @example(false)
    verbose: Boolean
  ) derives JsonSchema

  test("@example with Boolean literals produces JSON booleans") {
    val json = JsonSchema.toJson[WithBooleanExamples]
    val verbose = json.hcursor.downField("properties").downField("verbose")
    assertEquals(verbose.downField("type").as[String], Right("boolean"))
    assertEquals(verbose.downField("description").as[String], Right("Whether to enable verbose output"))
    val examples = verbose.downField("examples").focus.get
    assertEquals(examples, Json.arr(Json.fromBoolean(true), Json.fromBoolean(false)))
  }

  case class WithOptionAnnotation(
    @description("Optional nickname")
    @example("Bob")
    nickname: Option[String]
  ) derives JsonSchema

  test("annotations on Option fields") {
    val json = JsonSchema.toJson[WithOptionAnnotation]
    val nickname = json.hcursor.downField("properties").downField("nickname")
    assertEquals(nickname.downField("description").as[String], Right("Optional nickname"))
    assertEquals(nickname.downField("examples").as[List[String]], Right(List("Bob")))
    // Option fields use the inner type schema (not required, no oneOf)
    assertEquals(nickname.downField("type").as[String], Right("string"))
    val required = json.hcursor.downField("required").as[List[String]]
    assertEquals(required, Right(Nil))
  }

  case class WithListAnnotation(
    @description("Tags for the item")
    @example("important")
    tags: List[String]
  ) derives JsonSchema

  test("annotations on List fields") {
    val json = JsonSchema.toJson[WithListAnnotation]
    val tags = json.hcursor.downField("properties").downField("tags")
    assertEquals(tags.downField("type").as[String], Right("array"))
    assertEquals(tags.downField("description").as[String], Right("Tags for the item"))
    assertEquals(tags.downField("examples").as[List[String]], Right(List("important")))
  }

  case class Inner(x: Int) derives JsonSchema
  case class WithNestedAnnotation(
    @description("The inner object")
    inner: Inner
  ) derives JsonSchema

  test("annotations on nested case class fields") {
    val json = JsonSchema.toJson[WithNestedAnnotation]
    val inner = json.hcursor.downField("properties").downField("inner")
    assertEquals(inner.downField("description").as[String], Right("The inner object"))
    assertEquals(inner.downField("type").as[String], Right("object"))
  }

  case class SingleExample(
    @example("hello")
    word: String
  ) derives JsonSchema

  test("single @example produces single-element array") {
    val json = JsonSchema.toJson[SingleExample]
    val examples = json.hcursor.downField("properties").downField("word").downField("examples").as[List[String]]
    assertEquals(examples, Right(List("hello")))
  }

  case class MixedTypes(
    @example("hello") name: String,
    @example(42) count: Int,
    @example(3.14) ratio: Double,
    @example(true) enabled: Boolean
  ) derives JsonSchema

  test("@example with mixed types produces correct JSON types") {
    val json = JsonSchema.toJson[MixedTypes]
    val props = json.hcursor.downField("properties")

    assertEquals(
      props.downField("name").downField("examples").focus.get,
      Json.arr(Json.fromString("hello"))
    )
    assertEquals(
      props.downField("count").downField("examples").focus.get,
      Json.arr(Json.fromInt(42))
    )
    assertEquals(
      props.downField("ratio").downField("examples").focus.get,
      Json.arr(Json.fromDoubleOrNull(3.14))
    )
    assertEquals(
      props.downField("enabled").downField("examples").focus.get,
      Json.arr(Json.fromBoolean(true))
    )
  }

  case class MixedAnnotations(
    @description("First field") @example("a") first: String,
    second: Int,
    @example("x") @example("y") @example("z") third: String
  ) derives JsonSchema

  test("mixed annotations across multiple fields") {
    val json = JsonSchema.toJson[MixedAnnotations]
    val props = json.hcursor.downField("properties")

    // first: has both description and example
    assertEquals(props.downField("first").downField("description").as[String], Right("First field"))
    assertEquals(props.downField("first").downField("examples").as[List[String]], Right(List("a")))

    // second: no annotations at all
    assertEquals(props.downField("second").focus.get, Json.obj("type" -> Json.fromString("integer")))

    // third: examples only, three values, no description
    assert(props.downField("third").downField("description").failed)
    assertEquals(props.downField("third").downField("examples").as[List[String]], Right(List("x", "y", "z")))
  }
