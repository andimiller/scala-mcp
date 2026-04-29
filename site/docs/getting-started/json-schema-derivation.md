# JSON Schema derivation

The library provides automatic JSON Schema derivation using Scala 3's
`derives` clause, powered by the
[sttp-apispec](https://github.com/softwaremill/sttp-apispec) `Schema` type.
Annotations allow adding descriptions and examples:

```scala mdoc:silent
import io.circe.Decoder
import net.andimiller.mcp.core.schema.{JsonSchema, description, example}

case class SearchRequest(
  @description("The search query")
  query: String,
  @description("Maximum number of results")
  @example(10)
  maxResults: Int = 10,
  filters: Option[List[String]] = None
) derives JsonSchema, Decoder
```
