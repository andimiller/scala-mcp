package net.andimiller.mcp.openapi

import cats.effect.IO
import cats.syntax.all.*

import fs2.io.file.Files
import fs2.io.file.Path
import io.circe.Json
import io.circe.yaml.Parser as YamlParser
import org.http4s.MediaType
import org.http4s.Request
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.headers.Accept
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.given

object SpecLoader:

  /** Load and parse an OpenAPI spec from a URL or local file path. */
  def load(client: Client[IO], source: String): IO[OpenAPI] =
    for
      raw <-
        if source.startsWith("http://") || source.startsWith("https://") then fetchFromUrl(client, source)
        else readFromFile(source)
      json <- IO.fromEither(parseYamlOrJson(raw))
      spec <- IO.fromEither(json.as[OpenAPI])
    yield spec

  private def fetchFromUrl(client: Client[IO], url: String): IO[String] =
    for
      uri  <- IO.fromEither(Uri.fromString(url).leftMap(e => new Exception(s"Invalid URL: $url - ${e.message}")))
      body <- client.expect[String](
                Request[IO](uri = uri)
                  .putHeaders(
                    Accept(
                      MediaType.application.json,
                      MediaType.text.plain,
                      MediaType("application", "yaml"),
                      MediaType("*", "*")
                    )
                  )
              )
    yield body

  private def readFromFile(path: String): IO[String] =
    Files[IO].readUtf8(Path(path)).compile.string

  private def parseYamlOrJson(raw: String): Either[Throwable, Json] =
    // Try JSON first (faster), fall back to YAML
    io.circe.parser
      .parse(raw)
      .orElse(
        YamlParser.default.parse(raw).leftMap(e => e: io.circe.ParsingFailure)
      )
