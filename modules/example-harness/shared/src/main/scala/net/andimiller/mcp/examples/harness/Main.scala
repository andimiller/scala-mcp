package net.andimiller.mcp.examples.harness

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.*

import cats.data.Validated
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Console
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.Implementation

import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.Files
import fs2.io.file.Path as FsPath
import io.circe.parser.decode
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.http4s.client.middleware.Retry
import org.http4s.client.middleware.RetryPolicy
import org.http4s.ember.client.EmberClientBuilder

/** Tiny LLM ↔ MCP harness. Reads a Claude-style `.mcp.json`, opens every server it lists, hands those servers' tools to
  * an OpenAI-compatible chat endpoint, and runs a REPL.
  */
object Main
    extends CommandIOApp(
      name = "example-harness",
      header = "Mini LLM agent that drives MCP tools over an OpenAI-compatible endpoint",
      version = "0.1.0"
    ):

  private val clientInfo = Implementation("example-harness", "0.1.0")

  // ember-client raises TimeoutException from internal fibers when a pooled connection's liveness
  // probe fails — the Retry middleware then opens a fresh connection and the request succeeds.
  // The probe failure is harmless, but cats-effect's default reporter dumps the trace anyway.
  // Silently drop those; any other failure still gets the standard report.
  override protected def reportFailure(err: Throwable): IO[Unit] = err match
    case _: TimeoutException => IO.unit
    case _                   => super.reportFailure(err)

  private val configOpt: Opts[String] =
    Opts.option[String]("config", "Path to .mcp.json", short = "c")

  private val baseUrlOpt: Opts[Uri] =
    Opts.option[String]("base-url", "OpenAI-compatible base URL, e.g. https://api.openai.com/v1").mapValidated { s =>
      Uri.fromString(s).fold(t => Validated.invalidNel(s"invalid base-url '$s': ${t.getMessage}"), Validated.valid(_))
    }

  private val apiKeyOpt: Opts[String] =
    Opts.option[String]("api-key", "Bearer token for the LLM endpoint")

  private val modelOpt: Opts[String] =
    Opts.option[String]("model", "Model id, e.g. gpt-4o-mini, glm-5.1, claude-haiku-4-5")

  override def main: Opts[IO[ExitCode]] =
    (configOpt, baseUrlOpt, apiKeyOpt, modelOpt).mapN { (cfgPath, baseUrl, apiKey, model) =>
      runApp(cfgPath, baseUrl, apiKey, model).as(ExitCode.Success)
    }

  // POST is non-idempotent so http4s' default retriable refuses to retry it. We override that for
  // TimeoutException only — the most common cause is a stale keep-alive connection where the
  // request never reached the server, which is safe to replay. Capped at 2 retries with
  // exponential backoff to keep failures from being amplified.
  private val retryPolicy: RetryPolicy[IO] =
    val retriable: (Request[IO], Either[Throwable, Response[IO]]) => Boolean = {
      case (_, Left(_: TimeoutException)) => true
      case _                              => false
    }
    RetryPolicy[IO](RetryPolicy.exponentialBackoff(maxWait = 5.seconds, maxRetry = 2), retriable)

  private def runApp(cfgPath: String, baseUrl: Uri, apiKey: String, model: String): IO[Unit] =
    val resource = for
      cfg <- Resource.eval(loadConfig(cfgPath))
      _   <- Resource.eval(Console[IO].println(Theme.info(s"loaded ${cfg.mcpServers.size} server(s) from $cfgPath")))
      // 5 minute request timeout: chat completions can be slow on big contexts/tool-using runs.
      // 5 second idle pool time: aggressive eviction so we don't reuse a stale keep-alive
      // connection that the server already half-closed while the user was reading the previous
      // answer (the original symptom: TimeoutException in writeRead on a tiny follow-up prompt).
      raw <- EmberClientBuilder
               .default[IO]
               .withTimeout(5.minutes)
               .withIdleConnectionTime(5.seconds)
               .build
      http = Retry(retryPolicy)(raw)
      // The LLM client is built BEFORE the MCP clients because the ClientHandler (sampling +
      // elicitation) closes over it — servers can call back into the LLM via sampling/createMessage.
      llm                 = OpenAiClient[IO](http, baseUrl, apiKey, model)
      handler             = ClientHandlers.build[IO](llm, model)
      clients            <- McpClients.openAll[IO](cfg, clientInfo, ClientHandlers.capabilities, handler, http)
      _                  <- Resource.eval(Console[IO].println(Theme.info(s"connected: ${clients.keys.toList.sorted.mkString(", ")}")))
      _                  <- Notifications.watchAll[IO](clients)
      collected          <- Resource.eval(ToolBridge.collect[IO](clients))
      (tools, toolRoutes) = collected
      promptRoutes       <- Resource.eval(PromptBridge.collect[IO](clients))
      _                  <- Resource.eval(
             Console[IO].println(
               Theme.info(
                 s"${tools.size} tool(s), ${promptRoutes.size} prompt(s) — type a prompt, /help, or :q"
               )
             )
           )
    yield (llm, tools, toolRoutes, promptRoutes)

    resource.use { case (llm, tools, toolRoutes, promptRoutes) =>
      Repl.run[IO](llm, tools, toolRoutes, promptRoutes)
    }

  private def loadConfig(path: String): IO[McpJsonConfig] =
    Files[IO]
      .readAll(FsPath(path))
      .through(fs2.text.utf8.decode)
      .compile
      .string
      .flatMap { body =>
        IO.fromEither(
          decode[McpJsonConfig](body).leftMap(t => new RuntimeException(s"failed to parse $path: ${t.getMessage}"))
        )
      }
