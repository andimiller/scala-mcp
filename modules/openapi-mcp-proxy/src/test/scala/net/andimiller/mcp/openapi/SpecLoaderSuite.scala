package net.andimiller.mcp.openapi

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.HttpApp
import org.http4s.client.Client

class SpecLoaderSuite extends CatsEffectSuite:

  private val unusedClient: Client[IO] =
    Client.fromHttpApp(HttpApp.notFound[IO])

  private def fixturePath(name: String): String =
    val url = getClass.getClassLoader.getResource(name)
    assert(url != null, s"missing test resource: $name")
    url.getPath

  test("load: parses a JSON OpenAPI spec from a local file") {
    SpecLoader.load(unusedClient, fixturePath("petstore.json")).map { spec =>
      assertEquals(spec.info.title, "Petstore")
      assert(spec.paths.pathItems.keys.exists(_.toString.contains("/pets")), spec.paths.pathItems.keys.toList.toString)
    }
  }

  test("load: parses a YAML OpenAPI spec from a local file") {
    SpecLoader.load(unusedClient, fixturePath("petstore.yaml")).map { spec =>
      assertEquals(spec.info.title, "Petstore")
      assertEquals(spec.info.version, "1.0.0")
    }
  }

  test("load: invalid file path produces a meaningful error") {
    SpecLoader.load(unusedClient, "/does/not/exist.json").attempt.map {
      case Left(_)  => ()
      case Right(_) => fail("expected error for missing file")
    }
  }

  test("load: malformed JSON/YAML content produces a parse error") {
    val tmp = java.nio.file.Files.createTempFile("bad", ".json")
    try
      java.nio.file.Files.writeString(tmp, "{not valid json")
      SpecLoader.load(unusedClient, tmp.toString).attempt.map {
        case Left(_)  => ()
        case Right(_) => fail("expected error for malformed content")
      }
    finally java.nio.file.Files.deleteIfExists(tmp)
  }
