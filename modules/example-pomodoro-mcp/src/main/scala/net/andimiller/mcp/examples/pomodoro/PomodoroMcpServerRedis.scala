package net.andimiller.mcp.examples.pomodoro

import cats.effect.*
import com.comcast.ip4s.*
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log.Stdout.given
import dev.profunktor.redis4cats.pubsub.PubSub
import net.andimiller.mcp.http4s.McpHttp
import net.andimiller.mcp.redis.McpRedis

import scala.concurrent.duration.*

object PomodoroMcpServerRedis extends IOApp.Simple:

  final def run: IO[Unit] =
    (for
      client   <- RedisClient[IO].from("redis://localhost:6379")
      redis    <- Redis[IO].fromClient(client, RedisCodec.Utf8)
      pubSub   <- PubSub.mkPubSubConnection[IO, String, String](client, RedisCodec.Utf8)
      configure = McpRedis.configure[IO, Unit](redis, pubSub, 1.hour)
      server   <- PomodoroMcpServer
                  .configure(
                    configure(
                      McpHttp
                        .streaming[IO]
                        .name("pomodoro-mcp")
                        .version("1.0.0")
                        .port(port"25001")
                        .withExplorer(redirectToRoot = true)
                    )
                  )
                  .serve
    yield server).useForever
