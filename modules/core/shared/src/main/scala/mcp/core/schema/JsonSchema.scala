package mcp.core.schema

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import scala.compiletime.*
import scala.deriving.Mirror

/** Annotation for field descriptions */
case class description(value: String) extends scala.annotation.StaticAnnotation

/** JSON Schema value representation */
sealed trait JsonSchemaValue:
  def toJson: Json

/** Object schema with properties */
case class ObjectSchema(
  properties: Map[String, PropertySchema],
  required: List[String] = Nil,
  additionalProperties: Boolean = false
) extends JsonSchemaValue:
  def toJson: Json = Json.obj(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      properties.map { case (name, prop) => name -> prop.toJson }.toSeq*
    ),
    "required" -> required.asJson,
    "additionalProperties" -> additionalProperties.asJson
  )

/** Property schema with metadata */
case class PropertySchema(
  schema: JsonSchemaValue,
  description: Option[String] = None
) extends JsonSchemaValue:
  def toJson: Json =
    val base = schema.toJson.asObject.getOrElse(JsonObject.empty)
    val withDesc = description.fold(base)(d => base.add("description", d.asJson))
    Json.fromJsonObject(withDesc)

/** String schema */
case class StringSchema(
  description: Option[String] = None,
  enumValues: List[String] = Nil,
  minLength: Option[Int] = None,
  maxLength: Option[Int] = None
) extends JsonSchemaValue:
  def toJson: Json =
    val base = Json.obj("type" -> "string".asJson)
    val withDesc = description.fold(base.asObject.get)(d => base.asObject.get.add("description", d.asJson))
    val withEnum = if enumValues.nonEmpty then withDesc.add("enum", enumValues.asJson) else withDesc
    val withMin = minLength.fold(withEnum)(m => withEnum.add("minLength", m.asJson))
    val withMax = maxLength.fold(withMin)(m => withMin.add("maxLength", m.asJson))
    Json.fromJsonObject(withMax)

/** Number schema */
case class NumberSchema(
  description: Option[String] = None,
  minimum: Option[Double] = None,
  maximum: Option[Double] = None
) extends JsonSchemaValue:
  def toJson: Json =
    val base = Json.obj("type" -> "number".asJson)
    val withDesc = description.fold(base.asObject.get)(d => base.asObject.get.add("description", d.asJson))
    val withMin = minimum.fold(withDesc)(m => withDesc.add("minimum", m.asJson))
    val withMax = maximum.fold(withMin)(m => withMin.add("maximum", m.asJson))
    Json.fromJsonObject(withMax)

/** Integer schema */
case class IntegerSchema(
  description: Option[String] = None,
  minimum: Option[Int] = None,
  maximum: Option[Int] = None
) extends JsonSchemaValue:
  def toJson: Json =
    val base = Json.obj("type" -> "integer".asJson)
    val withDesc = description.fold(base.asObject.get)(d => base.asObject.get.add("description", d.asJson))
    val withMin = minimum.fold(withDesc)(m => withDesc.add("minimum", m.asJson))
    val withMax = maximum.fold(withMin)(m => withMin.add("maximum", m.asJson))
    Json.fromJsonObject(withMax)

/** Boolean schema */
case class BooleanSchema(
  description: Option[String] = None
) extends JsonSchemaValue:
  def toJson: Json =
    val base = Json.obj("type" -> "boolean".asJson)
    description.fold(base)(d =>
      Json.fromJsonObject(base.asObject.get.add("description", d.asJson))
    )

/** Array schema */
case class ArraySchema(
  items: JsonSchemaValue,
  description: Option[String] = None,
  minItems: Option[Int] = None,
  maxItems: Option[Int] = None
) extends JsonSchemaValue:
  def toJson: Json =
    val base = Json.obj(
      "type" -> "array".asJson,
      "items" -> items.toJson
    )
    val withDesc = description.fold(base.asObject.get)(d => base.asObject.get.add("description", d.asJson))
    val withMin = minItems.fold(withDesc)(m => withDesc.add("minItems", m.asJson))
    val withMax = maxItems.fold(withMin)(m => withMin.add("maxItems", m.asJson))
    Json.fromJsonObject(withMax)

/** Null schema */
case class NullSchema() extends JsonSchemaValue:
  def toJson: Json = Json.obj("type" -> "null".asJson)

/** Union schema (oneOf) */
case class UnionSchema(
  schemas: List[JsonSchemaValue]
) extends JsonSchemaValue:
  def toJson: Json = Json.obj(
    "oneOf" -> schemas.map(_.toJson).asJson
  )

/** Main JsonSchema type class */
trait JsonSchema[A]:
  def schema: JsonSchemaValue

object JsonSchema:
  // Summon instance
  def apply[A](using s: JsonSchema[A]): JsonSchema[A] = s

  // Get schema value
  def schemaFor[A](using s: JsonSchema[A]): JsonSchemaValue = s.schema

  // Get JSON representation
  def toJson[A](using s: JsonSchema[A]): Json = s.schema.toJson

  // Primitive instances
  given JsonSchema[String] with
    def schema: JsonSchemaValue = StringSchema()

  given JsonSchema[Int] with
    def schema: JsonSchemaValue = IntegerSchema()

  given JsonSchema[Long] with
    def schema: JsonSchemaValue = IntegerSchema()

  given JsonSchema[Double] with
    def schema: JsonSchemaValue = NumberSchema()

  given JsonSchema[Float] with
    def schema: JsonSchemaValue = NumberSchema()

  given JsonSchema[Boolean] with
    def schema: JsonSchemaValue = BooleanSchema()

  // Option support (makes field optional)
  given [A](using s: JsonSchema[A]): JsonSchema[Option[A]] with
    def schema: JsonSchemaValue = UnionSchema(List(s.schema, NullSchema()))

  // List support
  given [A](using s: JsonSchema[A]): JsonSchema[List[A]] with
    def schema: JsonSchemaValue = ArraySchema(s.schema)

  // Seq support
  given [A](using s: JsonSchema[A]): JsonSchema[Seq[A]] with
    def schema: JsonSchemaValue = ArraySchema(s.schema)

  // Derive for case classes using Mirror
  inline def derived[A](using m: Mirror.Of[A]): JsonSchema[A] =
    inline m match
      case p: Mirror.ProductOf[A] => deriveProduct[A](p)
      case s: Mirror.SumOf[A] => deriveSum[A](s)

  private inline def deriveProduct[A](p: Mirror.ProductOf[A]): JsonSchema[A] =
    new JsonSchema[A]:
      def schema: JsonSchemaValue =
        val labels = getLabels[p.MirroredElemLabels]
        val schemas = getSchemasForProduct[p.MirroredElemTypes]
        val descriptions = getDescriptions[A]

        val properties = labels.zip(schemas).map { case (name, schema) =>
          val desc = descriptions.get(name)
          name -> PropertySchema(schema, desc)
        }.toMap

        // Fields without Option are required
        val requiredFields = getRequiredFields[p.MirroredElemTypes](labels)

        ObjectSchema(properties, requiredFields, additionalProperties = false)

  private inline def deriveSum[A](s: Mirror.SumOf[A]): JsonSchema[A] =
    new JsonSchema[A]:
      def schema: JsonSchemaValue =
        val schemas = getSchemasForSum[s.MirroredElemTypes]
        UnionSchema(schemas)

  // Helper to get labels from tuple type
  private inline def getLabels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts) =>
        constValue[t].asInstanceOf[String] :: getLabels[ts]

  // Helper to get schemas for product types
  private inline def getSchemasForProduct[T <: Tuple]: List[JsonSchemaValue] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts) =>
        summonInline[JsonSchema[t]].schema :: getSchemasForProduct[ts]

  // Helper to get schemas for sum types
  private inline def getSchemasForSum[T <: Tuple]: List[JsonSchemaValue] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts) =>
        summonInline[JsonSchema[t]].schema :: getSchemasForSum[ts]

  // Helper to get required fields (non-Option types)
  private inline def getRequiredFields[T <: Tuple](labels: List[String]): List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (Option[t] *: ts) => getRequiredFields[ts](labels.tail)
      case _: (t *: ts) => labels.head :: getRequiredFields[ts](labels.tail)

  // Helper to extract description annotations (simplified version)
  private inline def getDescriptions[A]: Map[String, String] =
    // This would require more advanced macro work to extract annotations
    // For now, return empty map - can be enhanced later
    Map.empty

  // Builder-style API for manual schema construction
  object obj:
    def apply(properties: (String, JsonSchemaValue)*): ObjectSchema =
      ObjectSchema(
        properties.map { case (name, schema) => name -> PropertySchema(schema) }.toMap,
        required = properties.map(_._1).toList
      )

  object string:
    def apply(): StringSchema = StringSchema()
    def withDescription(desc: String): StringSchema = StringSchema(Some(desc))
    def withEnum(values: String*): StringSchema = StringSchema(enumValues = values.toList)

  object integer:
    def apply(): IntegerSchema = IntegerSchema()
    def withDescription(desc: String): IntegerSchema = IntegerSchema(Some(desc))
    def withRange(min: Int, max: Int): IntegerSchema = IntegerSchema(minimum = Some(min), maximum = Some(max))

  object number:
    def apply(): NumberSchema = NumberSchema()
    def withDescription(desc: String): NumberSchema = NumberSchema(Some(desc))

  object boolean:
    def apply(): BooleanSchema = BooleanSchema()
    def withDescription(desc: String): BooleanSchema = BooleanSchema(Some(desc))

  object array:
    def apply(items: JsonSchemaValue): ArraySchema = ArraySchema(items)
