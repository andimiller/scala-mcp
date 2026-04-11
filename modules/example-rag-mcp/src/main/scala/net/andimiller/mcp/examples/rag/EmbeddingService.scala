package net.andimiller.mcp.examples.rag

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel

trait EmbeddingService:
  def embed(text: String): IO[Array[Float]]
  def embedAll(texts: List[String]): IO[List[Array[Float]]]
  def dimensions: Int

object EmbeddingService:

  def resource: Resource[IO, EmbeddingService] =
    Resource.eval(IO.blocking(new AllMiniLmL6V2EmbeddingModel())).map { model =>
      new EmbeddingService:
        def dimensions: Int = 384

        def embed(text: String): IO[Array[Float]] =
          IO.blocking {
            model.embed(text).content().vector()
          }

        def embedAll(texts: List[String]): IO[List[Array[Float]]] =
          IO.blocking {
            import scala.jdk.CollectionConverters.*
            val segments = texts.map(dev.langchain4j.data.segment.TextSegment.from).asJava
            model.embedAll(segments).content().asScala.toList.map(_.vector())
          }
    }
