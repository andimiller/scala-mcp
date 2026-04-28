package net.andimiller.mcp.golden

import cats.effect.IO
import io.circe.Decoder
import io.circe.Encoder
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.Prompt
import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.core.server.ServerBuilder
import net.andimiller.mcp.core.server.tool

class McpGoldenSuiteSuite extends McpGoldenSuite:

  override def goldenFileName = "test-server.json"

  case class EchoRequest(message: String) derives JsonSchema, Decoder

  case class EchoResponse(echo: String) derives Encoder.AsObject, JsonSchema

  def server: IO[Server[IO]] =
    ServerBuilder[IO]("test-server", "1.0.0")
      .withTool(
        tool
          .name("echo")
          .description("Echoes the input message back")
          .in[EchoRequest]
          .out[EchoResponse]
          .run(r => IO.pure(EchoResponse(r.message)))
      )
      .withPrompt(Prompt.static[IO]("greet", List(PromptMessage.user("Hello!")), Some("A greeting")))
      .build
