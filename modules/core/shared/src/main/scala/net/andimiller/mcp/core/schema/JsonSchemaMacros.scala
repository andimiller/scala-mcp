package net.andimiller.mcp.core.schema

import scala.quoted.*
import sttp.apispec.ExampleSingleValue
import sttp.apispec.ExampleValue

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

  def getExamplesImpl[A: Type](using Quotes): Expr[Map[String, List[ExampleValue]]] =
    import quotes.reflect.*
    val sym = TypeRepr.of[A].typeSymbol
    val params = sym.primaryConstructor.paramSymss.flatten.filter(!_.isType)
    val pairs: List[Expr[(String, List[ExampleValue])]] = params.flatMap { param =>
      val examples: List[Expr[ExampleValue]] = param.annotations.collect {
        case ann if ann.tpe <:< TypeRepr.of[example] =>
          ann match
            case Apply(_, List(Literal(StringConstant(value))))  => '{ ExampleSingleValue(${ Expr(value) }) }
            case Apply(_, List(Literal(IntConstant(value))))     => '{ ExampleSingleValue(${ Expr(value) }) }
            case Apply(_, List(Literal(LongConstant(value))))    => '{ ExampleSingleValue(${ Expr(value) }) }
            case Apply(_, List(Literal(DoubleConstant(value))))  => '{ ExampleSingleValue(${ Expr(value) }) }
            case Apply(_, List(Literal(FloatConstant(value))))   => '{ ExampleSingleValue(${ Expr(value) }) }
            case Apply(_, List(Literal(BooleanConstant(value)))) => '{ ExampleSingleValue(${ Expr(value) }) }
      }.reverse
      if examples.nonEmpty then
        Some('{ (${ Expr(param.name) }, ${ Expr.ofList(examples) }) })
      else
        None
    }
    '{ Map(${ Expr.ofSeq(pairs) }*) }