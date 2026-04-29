package net.andimiller.mcp.openapi

import cats.effect.IO
import cats.syntax.all.*

import munit.CatsEffectSuite
import org.http4s.HttpApp
import org.http4s.Response
import org.http4s.Status
import org.http4s.client.Client
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.given

class OpenApiToolBuilderSuite extends CatsEffectSuite:

  val petStoreSpec: String =
    """
      |openapi: "3.1.0"
      |info:
      |  title: Petstore
      |  version: "1.0.0"
      |servers:
      |  - url: https://petstore.example.com/v1
      |paths:
      |  /pets:
      |    get:
      |      operationId: listPets
      |      summary: List all pets
      |      parameters:
      |        - name: limit
      |          in: query
      |          required: false
      |          schema:
      |            type: integer
      |      responses:
      |        '200':
      |          description: A list of pets
      |          content:
      |            application/json:
      |              schema:
      |                type: array
      |                items:
      |                  $ref: '#/components/schemas/Pet'
      |    post:
      |      operationId: createPet
      |      summary: Create a pet
      |      requestBody:
      |        required: true
      |        content:
      |          application/json:
      |            schema:
      |              $ref: '#/components/schemas/NewPet'
      |      responses:
      |        '201':
      |          description: The created pet
      |          content:
      |            application/json:
      |              schema:
      |                $ref: '#/components/schemas/Pet'
      |  /pets/{petId}:
      |    get:
      |      operationId: getPetById
      |      summary: Get a pet by ID
      |      parameters:
      |        - name: petId
      |          in: path
      |          required: true
      |          schema:
      |            type: string
      |      responses:
      |        '200':
      |          description: A pet
      |          content:
      |            application/json:
      |              schema:
      |                $ref: '#/components/schemas/Pet'
      |  /health:
      |    get:
      |      operationId: healthCheck
      |      summary: Health check
      |      responses:
      |        '200':
      |          description: OK
      |components:
      |  schemas:
      |    Pet:
      |      type: object
      |      required:
      |        - id
      |        - name
      |      properties:
      |        id:
      |          type: integer
      |        name:
      |          type: string
      |        tag:
      |          type: string
      |    NewPet:
      |      type: object
      |      required:
      |        - name
      |      properties:
      |        name:
      |          type: string
      |        tag:
      |          type: string
      |""".stripMargin

  val dummyClient: Client[IO] = Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](Status.Ok))))

  def parseSpec(yaml: String): IO[OpenAPI] =
    for
      json <- IO.fromEither(io.circe.yaml.Parser.default.parse(yaml))
      spec <- IO.fromEither(json.as[OpenAPI])
    yield spec

  test("builds ToolHandlers for specified operationIds") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(
                spec,
                List("listPets", "getPetById", "createPet"),
                dummyClient,
                "https://petstore.example.com/v1"
              )
    yield assertEquals(tools.map(_.name).toSet, Set("listPets", "getPetById", "createPet"))
  }

  test("listPets tool has correct inputSchema with query param") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(spec, List("listPets"), dummyClient, "https://petstore.example.com/v1")
    yield
      val tool  = tools.find(_.name === "listPets").get
      val props = tool.inputSchema.hcursor.downField("properties")
      assert(props.downField("limit").focus.isDefined, "should have 'limit' property")
      val required = tool.inputSchema.hcursor.downField("required").as[List[String]].getOrElse(Nil)
      assert(!required.contains("limit"), "limit should not be required")
  }

  test("listPets tool has correct description") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(spec, List("listPets"), dummyClient, "https://petstore.example.com/v1")
    yield
      val tool = tools.find(_.name === "listPets").get
      assertEquals(tool.description, "List all pets")
  }

  test("getPetById tool has path param as required") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(spec, List("getPetById"), dummyClient, "https://petstore.example.com/v1")
    yield
      val tool     = tools.find(_.name === "getPetById").get
      val required = tool.inputSchema.hcursor.downField("required").as[List[String]].getOrElse(Nil)
      assert(required.contains("petId"), "petId should be required")
  }

  test("createPet tool has body as required property") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(spec, List("createPet"), dummyClient, "https://petstore.example.com/v1")
    yield
      val tool  = tools.find(_.name === "createPet").get
      val props = tool.inputSchema.hcursor.downField("properties")
      assert(props.downField("body").focus.isDefined, "should have 'body' property")
      val required = tool.inputSchema.hcursor.downField("required").as[List[String]].getOrElse(Nil)
      assert(required.contains("body"), "body should be required")
  }

  test("$ref schemas are resolved (no $ref in output)") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(spec, List("getPetById"), dummyClient, "https://petstore.example.com/v1")
    yield
      val tool      = tools.find(_.name === "getPetById").get
      val outputStr = tool.outputSchema.get.noSpaces
      assert(!outputStr.contains("$ref"), s"outputSchema should have no $$ref: $outputStr")
  }

  test("array outputSchema is wrapped in object") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(spec, List("listPets"), dummyClient, "https://petstore.example.com/v1")
    yield
      val tool = tools.find(_.name === "listPets").get
      val out  = tool.outputSchema.get
      val typ  = out.hcursor.downField("type").as[String].getOrElse("")
      assertEquals(typ, "object", "array outputSchema should be wrapped in object")
      assert(
        out.hcursor.downField("properties").downField("items").focus.isDefined,
        "wrapped schema should have 'items' property"
      )
  }

  test("operation with no response schema falls back to object") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(spec, List("healthCheck"), dummyClient, "https://petstore.example.com/v1")
    yield
      val tool = tools.find(_.name === "healthCheck").get
      val typ  = tool.outputSchema.get.hcursor.downField("type").as[String].getOrElse("")
      assertEquals(typ, "object")
  }

  test("operation with no parameters has empty properties") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(spec, List("healthCheck"), dummyClient, "https://petstore.example.com/v1")
    yield
      val tool  = tools.find(_.name === "healthCheck").get
      val props = tool.inputSchema.hcursor.downField("properties").focus.flatMap(_.asObject)
      assert(props.exists(_.isEmpty), "healthCheck should have empty properties")
  }

  test("missing operationIds are silently skipped (logged to stderr)") {
    for
      spec <- parseSpec(petStoreSpec)
      tools = OpenApiToolBuilder.buildTools(
                spec,
                List("listPets", "nonExistent"),
                dummyClient,
                "https://petstore.example.com/v1"
              )
    yield
      assertEquals(tools.size, 1)
      assertEquals(tools.head.name, "listPets")
  }

  test("listOperationIds returns all operations from spec") {
    for spec <- parseSpec(petStoreSpec)
    yield
      val ops = OpenApiToolBuilder.listOperationIds(spec)
      assertEquals(ops.length, 4)
      assertEquals(
        ops.map(_._1).toSet,
        Set("listPets", "createPet", "getPetById", "healthCheck")
      )
      val listPets = ops.find(_._1 === "listPets").get
      assertEquals(listPets._2, "GET")
      assertEquals(listPets._3, "/pets")
  }
