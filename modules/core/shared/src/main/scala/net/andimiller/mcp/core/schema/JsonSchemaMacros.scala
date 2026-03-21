package net.andimiller.mcp.core.schema

import scala.quoted.*
import io.circe.Json

object JsonSchemaMacros:

  def getDescriptionsImpl[A: Type](using Quotes): Expr[Map[String, String]] =
    import quotes.reflect.*
    val sym = TypeRepr.of[A].typeSymbol
    val params = sym.primaryConstructor.paramSymss.flatten.filter(!_.isType)
    val pairs: List[Expr[(String, String)]] = params.flatMap { param =>
      param.annotations.collectFirst {
        case ann if ann.tpe <:< TypeRepr.of[description] =>
          ann match
            case Apply(_, List(Literal(StringConstant(value)))) =>
              '{ (${ Expr(param.name) }, ${ Expr(value) }) }
      }
    }
    '{ Map(${ Expr.ofSeq(pairs) }*) }

  def getExamplesImpl[A: Type](using Quotes): Expr[Map[String, List[Json]]] =
    import quotes.reflect.*
    val sym = TypeRepr.of[A].typeSymbol
    val params = sym.primaryConstructor.paramSymss.flatten.filter(!_.isType)
    val pairs: List[Expr[(String, List[Json])]] = params.flatMap { param =>
      val examples: List[Expr[Json]] = param.annotations.collect {
        case ann if ann.tpe <:< TypeRepr.of[example] =>
          ann match
            case Apply(_, List(Literal(StringConstant(value))))  => '{ Json.fromString(${ Expr(value) }) }
            case Apply(_, List(Literal(IntConstant(value))))     => '{ Json.fromInt(${ Expr(value) }) }
            case Apply(_, List(Literal(LongConstant(value))))    => '{ Json.fromLong(${ Expr(value) }) }
            case Apply(_, List(Literal(DoubleConstant(value))))  => '{ Json.fromDoubleOrNull(${ Expr(value) }) }
            case Apply(_, List(Literal(FloatConstant(value))))   => '{ Json.fromFloatOrNull(${ Expr(value) }) }
            case Apply(_, List(Literal(BooleanConstant(value)))) => '{ Json.fromBoolean(${ Expr(value) }) }
      }.reverse
      if examples.nonEmpty then
        Some('{ (${ Expr(param.name) }, ${ Expr.ofList(examples) }) })
      else
        None
    }
    '{ Map(${ Expr.ofSeq(pairs) }*) }
