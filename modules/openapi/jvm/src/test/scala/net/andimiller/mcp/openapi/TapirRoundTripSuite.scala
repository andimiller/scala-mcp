package net.andimiller.mcp.openapi

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.given
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

class TapirRoundTripSuite extends CatsEffectSuite:

  private val interpreter = OpenAPIDocsInterpreter()

  test("parameter description from tapir endpoint appears in inputSchema") {
    val getUser = endpoint
      .get
      .in("users" / path[String]("id").description("The user ID"))
      .out(stringBody)
      .name("getUser")

    val spec = interpreter.toOpenAPI(getUser, "Test API", "1.0.0")
    val ops = OpenApiOperation.build(spec, List("getUser"))

    assertEquals(ops.length, 1)
    val input = ops.head.definition.inputSchema
    val idDesc = input.hcursor.downField("properties").downField("id").downField("description").as[String].toOption
    assertEquals(idDesc, Some("The user ID"))
  }

  test("query parameter description and example appear in inputSchema") {
    val listUsers = endpoint
      .get
      .in("users")
      .in(query[Int]("limit").description("Max results").example(10))
      .out(stringBody)
      .name("listUsers")

    val spec = interpreter.toOpenAPI(listUsers, "Test API", "1.0.0")
    val ops = OpenApiOperation.build(spec, List("listUsers"))

    assertEquals(ops.length, 1)
    val input = ops.head.definition.inputSchema
    val props = input.hcursor.downField("properties")
    val limitDesc = props.downField("limit").downField("description").as[String].toOption
    assertEquals(limitDesc, Some("Max results"))
    val limitExample = props.downField("limit").downField("example").as[Int].toOption
    assertEquals(limitExample, Some(10))
  }

  test("path and query parameters both preserve descriptions") {
    val getItems = endpoint
      .get
      .in("items" / path[String]("itemId").description("The item ID"))
      .in(query[Int]("count").description("Number of items").example(5))
      .out(stringBody)
      .name("getItems")

    val spec = interpreter.toOpenAPI(getItems, "Test API", "1.0.0")
    val ops = OpenApiOperation.build(spec, List("getItems"))

    assertEquals(ops.length, 1)
    val input = ops.head.definition.inputSchema
    val props = input.hcursor.downField("properties")
    val itemDesc = props.downField("itemId").downField("description").as[String].toOption
    assertEquals(itemDesc, Some("The item ID"))
    val countDesc = props.downField("count").downField("description").as[String].toOption
    assertEquals(countDesc, Some("Number of items"))
    val countExample = props.downField("count").downField("example").as[Int].toOption
    assertEquals(countExample, Some(5))
  }

  test("operation summary becomes tool description") {
    val endpoint1 = endpoint
      .get
      .in("health")
      .out(stringBody)
      .summary("Health check endpoint")
      .description("Returns OK if the service is healthy")
      .name("healthCheck")

    val spec = interpreter.toOpenAPI(endpoint1, "Test API", "1.0.0")
    val ops = OpenApiOperation.build(spec, List("healthCheck"))

    assertEquals(ops.length, 1)
    assertEquals(ops.head.definition.description, "Health check endpoint")
  }

  test("operation description is used when summary is absent") {
    val endpoint1 = endpoint
      .get
      .in("health")
      .out(stringBody)
      .description("Returns OK if the service is healthy")
      .name("healthCheck")

    val spec = interpreter.toOpenAPI(endpoint1, "Test API", "1.0.0")
    val ops = OpenApiOperation.build(spec, List("healthCheck"))

    assertEquals(ops.length, 1)
    assertEquals(ops.head.definition.description, "Returns OK if the service is healthy")
  }

  test("parameter without description has no description field in schema") {
    val simple = endpoint
      .get
      .in("things")
      .in(query[Int]("count"))
      .out(stringBody)
      .name("listThings")

    val spec = interpreter.toOpenAPI(simple, "Test API", "1.0.0")
    val ops = OpenApiOperation.build(spec, List("listThings"))

    assertEquals(ops.length, 1)
    val input = ops.head.definition.inputSchema
    val countDesc = input.hcursor.downField("properties").downField("count").downField("description").as[String].toOption
    assertEquals(countDesc, None)
  }

  test("parameter example value of different types") {
    val search = endpoint
      .get
      .in("search")
      .in(query[String]("q").description("Search query").example("hello"))
      .out(stringBody)
      .name("search")

    val spec = interpreter.toOpenAPI(search, "Test API", "1.0.0")
    val ops = OpenApiOperation.build(spec, List("search"))

    assertEquals(ops.length, 1)
    val props = ops.head.definition.inputSchema.hcursor.downField("properties")
    val qExample = props.downField("q").downField("example").as[String].toOption
    assertEquals(qExample, Some("hello"))
  }

  test("YAML spec with parameter descriptions round-trips through OpenApiOperation") {
    val yaml =
      """
      |openapi: "3.1.0"
      |info:
      |  title: Petstore
      |  version: "1.0.0"
      |paths:
      |  /pets/{petId}:
      |    get:
      |      operationId: getPetById
      |      summary: Get a pet by ID
      |      parameters:
      |        - name: petId
      |          in: path
      |          required: true
      |          description: The unique identifier of the pet
      |          example: "abc123"
      |          schema:
      |            type: string
      |      responses:
      |        '200':
      |          description: A pet
      |""".stripMargin

    for
      json <- IO.fromEither(io.circe.yaml.Parser.default.parse(yaml))
      spec <- IO.fromEither(json.as[OpenAPI])
      ops = OpenApiOperation.build(spec, List("getPetById"))
    yield
      assertEquals(ops.length, 1)
      val props = ops.head.definition.inputSchema.hcursor.downField("properties")
      val petIdDesc = props.downField("petId").downField("description").as[String].toOption
      assertEquals(petIdDesc, Some("The unique identifier of the pet"))
      val petIdExample = props.downField("petId").downField("example").as[String].toOption
      assertEquals(petIdExample, Some("abc123"))
  }

  test("YAML spec with query parameter description and example") {
    val yaml =
      """
      |openapi: "3.1.0"
      |info:
      |  title: Search
      |  version: "1.0.0"
      |paths:
      |  /search:
      |    get:
      |      operationId: search
      |      summary: Search items
      |      parameters:
      |        - name: q
      |          in: query
      |          description: Search query string
      |          example: hello world
      |          schema:
      |            type: string
      |        - name: limit
      |          in: query
      |          description: Maximum results
      |          example: 10
      |          schema:
      |            type: integer
      |      responses:
      |        '200':
      |          description: Results
      |""".stripMargin

    for
      json <- IO.fromEither(io.circe.yaml.Parser.default.parse(yaml))
      spec <- IO.fromEither(json.as[OpenAPI])
      ops = OpenApiOperation.build(spec, List("search"))
    yield
      assertEquals(ops.length, 1)
      val props = ops.head.definition.inputSchema.hcursor.downField("properties")
      val qDesc = props.downField("q").downField("description").as[String].toOption
      assertEquals(qDesc, Some("Search query string"))
      val qExample = props.downField("q").downField("example").as[String].toOption
      assertEquals(qExample, Some("hello world"))
      val limitDesc = props.downField("limit").downField("description").as[String].toOption
      assertEquals(limitDesc, Some("Maximum results"))
      val limitExample = props.downField("limit").downField("example").as[Int].toOption
      assertEquals(limitExample, Some(10))
  }

  test("YAML spec with request body description") {
    val yaml =
      """
      |openapi: "3.1.0"
      |info:
      |  title: Pets
      |  version: "1.0.0"
      |paths:
      |  /pets:
      |    post:
      |      operationId: createPet
      |      summary: Create a pet
      |      requestBody:
      |        description: Pet object to add
      |        required: true
      |        content:
      |          application/json:
      |            schema:
      |              type: object
      |              properties:
      |                name:
      |                  type: string
      |                age:
      |                  type: integer
      |      responses:
      |        '201':
      |          description: Created
      |""".stripMargin

    for
      json <- IO.fromEither(io.circe.yaml.Parser.default.parse(yaml))
      spec <- IO.fromEither(json.as[OpenAPI])
      ops = OpenApiOperation.build(spec, List("createPet"))
    yield
      assertEquals(ops.length, 1)
      val bodySchema = ops.head.definition.inputSchema.hcursor.downField("properties").downField("body")
      val bodyDesc = bodySchema.downField("description").as[String].toOption
      assertEquals(bodyDesc, Some("Pet object to add"))
  }