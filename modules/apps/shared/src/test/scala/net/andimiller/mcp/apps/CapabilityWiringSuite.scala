package net.andimiller.mcp.apps

import cats.effect.IO

import io.circe.syntax.*
import munit.CatsEffectSuite

import net.andimiller.mcp.apps.syntax.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.server.ServerBuilder

class CapabilityWiringSuite extends CatsEffectSuite:

  test("withAppsExtension declares io.modelcontextprotocol/ui in the InitializeResponse") {
    ServerBuilder[IO]("test-server", "0.1.0").withAppsExtension.build.map { server =>
      val response = InitializeResponse(
        protocolVersion = "2025-11-25",
        capabilities = server.capabilities,
        serverInfo = server.info
      )
      val json     = response.asJson.deepDropNullValues
      val cursor   = json.hcursor.downField("capabilities").downField("extensions").downField(AppsExtension.ExtensionKey)
      assertEquals(cursor.downField("mimeTypes").as[List[String]], Right(List(AppsExtension.MimeType)))
    }
  }

  test("withExtension composes multiple extensions without trampling") {
    ServerBuilder[IO]("test-server", "0.1.0")
      .withAppsExtension
      .withExtension("com.example/custom", io.circe.Json.obj("foo" -> "bar".asJson))
      .build
      .map { server =>
        val extensions = server.capabilities.extensions.get
        assert(extensions(AppsExtension.ExtensionKey).isDefined)
        assert(extensions("com.example/custom").isDefined)
      }
  }
