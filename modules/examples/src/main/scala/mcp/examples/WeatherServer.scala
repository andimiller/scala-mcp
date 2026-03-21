package mcp.examples

import cats.effect.{IO, IOApp}
import cats.syntax.traverse.*
import io.circe.{Decoder, Encoder}
import mcp.core.schema.JsonSchema
import mcp.core.server.{ServerBuilder, Tool}
import sourcecode.Name

/**
 * Simple weather server example demonstrating the MCP library.
 *
 * This example shows:
 * - Case classes with automatic schema derivation using `derives`
 * - Tool creation using Tool.build with automatic schema generation
 * - Server construction using the builder API
 */
object WeatherServer extends IOApp.Simple:

  // Request type with automatic derivation
  case class WeatherRequest(
    location: String,
    units: Option[String] = Some("celsius")
  ) derives JsonSchema, Decoder

  // Response type
  case class WeatherResponse(
    temperature: Double,
    conditions: String,
    humidity: Int
  ) derives Encoder, JsonSchema

  // Define a simple weather tool using automatic schema derivation
  val getWeatherTool = Tool.buildNamed[IO, WeatherRequest, WeatherResponse](
    "get_weather",
    "Get current weather for a location"
  ) { request =>
    // Simulate weather lookup
    IO.pure(WeatherResponse(
      temperature = 22.5,
      conditions = "Sunny",
      humidity = 65
    ))
  }

  // Alternative: using the builder API
  val getWeatherBuilder = Tool.builder[IO]
    .name("get_weather_builder")
    .description("Get current weather for a location (using builder)")
    .schemaFrom[WeatherRequest]
    .handler { (request: WeatherRequest) =>
      IO.pure(WeatherResponse(
        temperature = 22.5,
        conditions = "Sunny",
        humidity = 65
      ))
    }

  // Build the server
  def run: IO[Unit] =
    for
      server <- ServerBuilder[IO]("weather-server", "1.0.0")
        .withTool(getWeatherTool)
        .withTool(getWeatherBuilder)
        .build

      // Print server info
      _ <- IO.println(s"Weather Server: ${server.info.name} v${server.info.version}")
      _ <- IO.println(s"Capabilities: ${server.capabilities}")

      // Test tool listing
      tools <- server.listTools(mcp.core.protocol.ListToolsRequest())
      _ <- IO.println(s"\nAvailable tools (${tools.tools.size}):")
      _ <- tools.tools.traverse { tool =>
        IO.println(s"  - ${tool.name}: ${tool.description}")
      }

      // Test calling a tool
      _ <- IO.println("\nTesting tool call...")
      request = mcp.core.protocol.CallToolRequest(
        name = "get_weather",
        arguments = io.circe.parser.parse("""{"location": "San Francisco"}""").toOption.get
      )
      response <- server.callTool(request)
      _ <- IO.println(s"Result: ${response.content}")

    yield ()
