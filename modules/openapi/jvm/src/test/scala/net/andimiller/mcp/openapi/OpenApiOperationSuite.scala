package net.andimiller.mcp.openapi

import cats.effect.IO
import munit.CatsEffectSuite
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.given

class OpenApiOperationSuite extends CatsEffectSuite:

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

  def parseSpec(yaml: String): IO[OpenAPI] =
    for
      json <- IO.fromEither(io.circe.yaml.Parser.default.parse(yaml))
      spec <- IO.fromEither(json.as[OpenAPI])
    yield spec

  test("builds operations for specified operationIds") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("listPets", "getPetById", "createPet"))
    yield
      assertEquals(operations.map(_.definition.name).toSet, Set("listPets", "getPetById", "createPet"))
  }

  test("listPets operation has correct inputSchema with query param") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("listPets"))
      op         = operations.find(_.definition.name == "listPets").get
    yield
      val props = op.definition.inputSchema.hcursor.downField("properties")
      assert(props.downField("limit").focus.isDefined, "should have 'limit' property")
      val required = op.definition.inputSchema.hcursor.downField("required").as[List[String]].getOrElse(Nil)
      assert(!required.contains("limit"), "limit should not be required")
  }

  test("listPets operation has correct description") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("listPets"))
      op         = operations.find(_.definition.name == "listPets").get
    yield
      assertEquals(op.definition.description, "List all pets")
  }

  test("getPetById operation has path param as required") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("getPetById"))
      op         = operations.find(_.definition.name == "getPetById").get
    yield
      val required = op.definition.inputSchema.hcursor.downField("required").as[List[String]].getOrElse(Nil)
      assert(required.contains("petId"), "petId should be required")
  }

  test("createPet operation has body as required property") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("createPet"))
      op         = operations.find(_.definition.name == "createPet").get
    yield
      val props = op.definition.inputSchema.hcursor.downField("properties")
      assert(props.downField("body").focus.isDefined, "should have 'body' property")
      val required = op.definition.inputSchema.hcursor.downField("required").as[List[String]].getOrElse(Nil)
      assert(required.contains("body"), "body should be required")
  }

  test("$ref schemas are resolved (no $ref in output)") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("getPetById"))
      op         = operations.find(_.definition.name == "getPetById").get
    yield
      val outputStr = op.definition.outputSchema.get.noSpaces
      assert(!outputStr.contains("$ref"), s"outputSchema should have no $$ref: $outputStr")
  }

  test("array outputSchema is wrapped in object") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("listPets"))
      op         = operations.find(_.definition.name == "listPets").get
    yield
      val out = op.definition.outputSchema.get
      val typ = out.hcursor.downField("type").as[String].getOrElse("")
      assertEquals(typ, "object", "array outputSchema should be wrapped in object")
      assert(out.hcursor.downField("properties").downField("items").focus.isDefined,
        "wrapped schema should have 'items' property")
  }

  test("operation with no response schema falls back to object") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("healthCheck"))
      op         = operations.find(_.definition.name == "healthCheck").get
    yield
      val typ = op.definition.outputSchema.get.hcursor.downField("type").as[String].getOrElse("")
      assertEquals(typ, "object")
  }

  test("operation with no parameters has empty properties") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("healthCheck"))
      op         = operations.find(_.definition.name == "healthCheck").get
    yield
      val props = op.definition.inputSchema.hcursor.downField("properties").focus.flatMap(_.asObject)
      assert(props.exists(_.isEmpty), "healthCheck should have empty properties")
  }

  test("missing operationIds are silently skipped") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("listPets", "nonExistent"))
    yield
      assertEquals(operations.size, 1)
      assertEquals(operations.head.definition.name, "listPets")
  }

  test("listOperationIds returns all operations from spec") {
    for
      spec <- parseSpec(petStoreSpec)
      ops  = OpenApiOperation.listOperationIds(spec)
    yield
      assertEquals(ops.length, 4)
      assertEquals(
        ops.map(_._1).toSet,
        Set("listPets", "createPet", "getPetById", "healthCheck")
      )
      val listPets = ops.find(_._1 == "listPets").get
      assertEquals(listPets._2, "GET")
      assertEquals(listPets._3, "/pets")
  }

  test("operations carry correct method and path") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("createPet"))
      op         = operations.find(_.definition.name == "createPet").get
    yield
      assertEquals(op.method, "POST")
      assertEquals(op.pathPattern, "/pets")
  }

  test("operations carry resolved operation details") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("getPetById"))
      op         = operations.find(_.definition.name == "getPetById").get
    yield
      assertEquals(op.resolvedOperation.pathParams.map(_.name), List("petId"))
      assert(op.resolvedOperation.pathParams.head.required, "petId should be required")
      assertEquals(op.resolvedOperation.hasBody, false)
  }

  test("createPet operation has body in resolved operation") {
    for
      spec       <- parseSpec(petStoreSpec)
      operations = OpenApiOperation.build(spec, List("createPet"))
      op         = operations.find(_.definition.name == "createPet").get
    yield
      assert(op.resolvedOperation.hasBody, "createPet should have body")
  }