package net.andimiller.mcp.core.schema

import io.circe.Json
import io.circe.syntax.*
import sttp.apispec.*
import sttp.apispec.circe.given
import scala.collection.immutable.ListMap
import scala.compiletime.*
import scala.deriving.Mirror
import scala.annotation.nowarn

case class description(value: String) extends scala.annotation.StaticAnnotation

case class example(value: Any) extends scala.annotation.StaticAnnotation

trait JsonSchema[A]:

  def schema: Schema

object JsonSchema:

  def apply[A](using s: JsonSchema[A]): JsonSchema[A] = s

  def schemaFor[A](using s: JsonSchema[A]): Schema = s.schema

  def toJson[A](using s: JsonSchema[A]): Json = s.schema.asJson

  given JsonSchema[String] with

    def schema: Schema = Schema(`type` = Some(List(SchemaType.String)))

  given JsonSchema[Int] with

    def schema: Schema = Schema(`type` = Some(List(SchemaType.Integer)))

  given JsonSchema[Long] with

    def schema: Schema = Schema(`type` = Some(List(SchemaType.Integer)))

  given JsonSchema[Double] with

    def schema: Schema = Schema(`type` = Some(List(SchemaType.Number)))

  given JsonSchema[Float] with

    def schema: Schema = Schema(`type` = Some(List(SchemaType.Number)))

  given JsonSchema[Boolean] with

    def schema: Schema = Schema(`type` = Some(List(SchemaType.Boolean)))

  given [A](using s: JsonSchema[A]): JsonSchema[Option[A]] with

    def schema: Schema = s.schema

  given [A](using s: JsonSchema[A]): JsonSchema[List[A]] with

    def schema: Schema = Schema(`type` = Some(List(SchemaType.Array)), items = Some(s.schema: SchemaLike))

  given [A](using s: JsonSchema[A]): JsonSchema[Seq[A]] with

    def schema: Schema = Schema(`type` = Some(List(SchemaType.Array)), items = Some(s.schema: SchemaLike))

  inline def derived[A](using m: Mirror.Of[A]): JsonSchema[A] =
    inline m match
      case p: Mirror.ProductOf[A] => deriveProduct[A](p)
      case s: Mirror.SumOf[A]     => deriveSum[A](s)

  @nowarn("msg=New anonymous class definition will be duplicated at each inline site")
  private inline def deriveProduct[A](p: Mirror.ProductOf[A]): JsonSchema[A] =
    new JsonSchema[A]:
      def schema: Schema =
        val labels       = getLabels[p.MirroredElemLabels]
        val schemas      = getSchemasForProduct[p.MirroredElemTypes]
        val descriptions = getDescriptions[A]
        val examples     = getExamples[A]

        val fields = labels.zip(schemas).map { (name, schema) =>
          val enriched = schema.copy(
            description = descriptions.get(name).orElse(schema.description),
            examples = examples.get(name).orElse(schema.examples)
          )
          name -> (enriched: SchemaLike)
        }

        val required = getRequiredFields[p.MirroredElemTypes](labels)

        Schema(
          `type` = Some(List(SchemaType.Object)),
          properties = ListMap(fields*),
          required = required,
          additionalProperties = Some(AnySchema.Nothing)
        )

  @nowarn("msg=New anonymous class definition will be duplicated at each inline site")
  private inline def deriveSum[A](s: Mirror.SumOf[A]): JsonSchema[A] =
    new JsonSchema[A]:
      def schema: Schema =
        val schemas = getSchemasForSum[s.MirroredElemTypes]
        Schema(oneOf = schemas)

  private inline def getLabels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  =>
        constValue[t].asInstanceOf[String] :: getLabels[ts]

  private inline def getSchemasForProduct[T <: Tuple]: List[Schema] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  =>
        summonInline[JsonSchema[t]].schema :: getSchemasForProduct[ts]

  private inline def getSchemasForSum[T <: Tuple]: List[SchemaLike] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  =>
        (summonInline[JsonSchema[t]].schema: SchemaLike) :: getSchemasForSum[ts]

  private inline def getRequiredFields[T <: Tuple](labels: List[String]): List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple        => Nil
      case _: (Option[t] *: ts) => getRequiredFields[ts](labels.tail)
      case _: (t *: ts)         => labels.head :: getRequiredFields[ts](labels.tail)

  private inline def getDescriptions[A]: Map[String, String] =
    ${ JsonSchemaMacros.getDescriptionsImpl[A] }

  private inline def getExamples[A]: Map[String, List[ExampleValue]] =
    ${ JsonSchemaMacros.getExamplesImpl[A] }

  object obj:

    def apply(fields: (String, Schema)*): Schema =
      Schema(
        `type` = Some(List(SchemaType.Object)),
        properties = ListMap(fields.map((name, schema) => name -> (schema: SchemaLike))*),
        required = fields.map(_._1).toList,
        additionalProperties = Some(AnySchema.Nothing)
      )

  object string:

    def apply(): Schema = Schema(`type` = Some(List(SchemaType.String)))

    def withDescription(desc: String): Schema = Schema(`type` = Some(List(SchemaType.String)), description = Some(desc))

    def withEnum(values: String*): Schema =
      Schema(`type` = Some(List(SchemaType.String)), `enum` = Some(values.map(ExampleSingleValue(_)).toList))

  object integer:

    def apply(): Schema = Schema(`type` = Some(List(SchemaType.Integer)))

    def withDescription(desc: String): Schema =
      Schema(`type` = Some(List(SchemaType.Integer)), description = Some(desc))

    def withRange(min: Int, max: Int): Schema =
      Schema(`type` = Some(List(SchemaType.Integer)), minimum = Some(BigDecimal(min)), maximum = Some(BigDecimal(max)))

  object number:

    def apply(): Schema = Schema(`type` = Some(List(SchemaType.Number)))

    def withDescription(desc: String): Schema = Schema(`type` = Some(List(SchemaType.Number)), description = Some(desc))

  object boolean:

    def apply(): Schema = Schema(`type` = Some(List(SchemaType.Boolean)))

    def withDescription(desc: String): Schema =
      Schema(`type` = Some(List(SchemaType.Boolean)), description = Some(desc))

  object array:

    def apply(items: Schema): Schema =
      Schema(`type` = Some(List(SchemaType.Array)), items = Some(items: SchemaLike))
