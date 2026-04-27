package net.andimiller.mcp.examples.chat

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder}
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.Stdout.given
import net.andimiller.mcp.core.protocol.{PromptArgument, PromptMessage, ResourceContent}
import net.andimiller.mcp.core.schema.{JsonSchema, description}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.state.{SessionRefs, StateRef}
import net.andimiller.mcp.http4s.McpHttp
import net.andimiller.mcp.redis.McpRedis

import scala.concurrent.duration.*

// ── Per-session context ──────────────────────────────────────────────

class ChatSession(
  val username: StateRef[IO, Option[String]],
  val currentRoom: StateRef[IO, Option[String]],
  val chat: ChatService,
  val sink: NotificationSink[IO]
)

object ChatSession:
  def create(sink: NotificationSink[IO], refs: SessionRefs[IO], chat: ChatService): IO[ChatSession] =
    for
      username    <- refs.ref[Option[String]]("username", None)
      currentRoom <- refs.ref[Option[String]]("current_room", None)
    yield ChatSession(username, currentRoom, chat, sink)

// ── Request / response types ─────────────────────────────────────────

case class SetUsernameRequest(
  @description("Display name for this session")
  name: String
) derives JsonSchema, Decoder

case class CreateRoomRequest(
  @description("Name of the room to create")
  name: String
) derives JsonSchema, Decoder

case class JoinRoomRequest(
  @description("Name of the room to join")
  room: String
) derives JsonSchema, Decoder

case class SendMessageRequest(
  @description("Message content to send to the current room")
  content: String
) derives JsonSchema, Decoder

case class ReadMessagesRequest(
  @description("Name of the room to read messages from")
  room: String,
  @description("Number of recent messages to retrieve")
  count: Option[Int]
) derives JsonSchema, Decoder

case class MessageResponse(message: String) derives Encoder.AsObject, JsonSchema
case class RoomListResponse(rooms: List[String]) derives Encoder.AsObject, JsonSchema
case class ChatMessagesResponse(messages: List[ChatMessageView]) derives Encoder.AsObject, JsonSchema
case class ChatMessageView(sender: String, content: String, timestamp: Long) derives Encoder.AsObject, JsonSchema

// ── Server ───────────────────────────────────────────────────────────

object ChatMcpServer extends IOApp.Simple:

  final def run: IO[Unit] =
    (for
      client  <- RedisClient[IO].from("redis://localhost:6379")
      redis   <- Redis[IO].fromClient(client, RedisCodec.Utf8)
      pubSub  <- PubSub.mkPubSubConnection[IO, String, String](client, RedisCodec.Utf8)
      service  = ChatService(redis)
      configure = McpRedis.configure[IO, Unit](redis, pubSub, 1.hour)
      server  <- configure(
          McpHttp.streaming[IO]
            .name("chat-mcp")
            .version("1.0.0")
            .port(port"27000")
            .withExplorer(redirectToRoot = true)
        )
        .stateful[ChatSession](ctx =>
          ChatSession.create(ctx.sink, ctx.refs, service)
        )
        // ── tools ────────────────────────────────────────────────────
        .withContextualTool(
          contextualTool[ChatSession].name("set_username")
            .description("Set your display name for this chat session")
            .in[SetUsernameRequest]
            .run { (session, req) =>
              session.username.set(Some(req.name))
                .as(MessageResponse(s"Username set to '${req.name}'"))
            }
        )
        .withContextualTool(
          contextualTool[ChatSession].name("create_room")
            .description("Create a new chat room and join it")
            .in[CreateRoomRequest]
            .run { (session, req) =>
              for
                user <- session.username.get.flatMap {
                  case Some(u) => IO.pure(u)
                  case None    => IO.raiseError(Exception("Set a username first with set_username"))
                }
                room <- session.chat.createRoom(req.name, user)
                _    <- session.currentRoom.set(Some(req.name))
              yield MessageResponse(s"Room '${req.name}' created and joined")
            }
        )
        .withContextualTool(
          contextualTool[ChatSession].name("join_room")
            .description("Join an existing chat room")
            .in[JoinRoomRequest]
            .run { (session, req) =>
              for
                user <- session.username.get.flatMap {
                  case Some(u) => IO.pure(u)
                  case None    => IO.raiseError(Exception("Set a username first with set_username"))
                }
                _ <- session.chat.joinRoom(req.room, user)
                _ <- session.currentRoom.set(Some(req.room))
              yield MessageResponse(s"Joined room '${req.room}'")
            }
        )
        .withContextualTool(
          contextualTool[ChatSession].name("send_message")
            .description("Send a message to your current room")
            .in[SendMessageRequest]
            .run { (session, req) =>
              for
                user <- session.username.get.flatMap {
                  case Some(u) => IO.pure(u)
                  case None    => IO.raiseError(Exception("Set a username first with set_username"))
                }
                room <- session.currentRoom.get.flatMap {
                  case Some(r) => IO.pure(r)
                  case None    => IO.raiseError(Exception("Join a room first with join_room"))
                }
                msg <- session.chat.sendMessage(room, user, req.content)
                _   <- session.sink.resourceUpdated(s"chat://rooms/$room/messages")
              yield MessageResponse(s"Message sent to '$room'")
            }
        )
        .withContextualTool(
          contextualTool[ChatSession].name("read_messages")
            .description("Read recent messages from a room")
            .in[ReadMessagesRequest]
            .run { (session, req) =>
              session.chat.getMessages(req.room, req.count.getOrElse(50)).map { msgs =>
                ChatMessagesResponse(msgs.map(m => ChatMessageView(m.sender, m.content, m.timestamp)))
              }
            }
        )
        // ── resources ────────────────────────────────────────────────
        .withResource(
          resource
            .uri("chat://rooms")
            .name("Chat Rooms")
            .description("List of all available chat rooms")
            .mimeType("text/plain")
            .read(() => service.listRooms.map(_.mkString("\n")))
        )
        // ── resource templates ───────────────────────────────────────
        .withResourceTemplate(
          resourceTemplate
            .path(path.static("chat://rooms/") *> path.named("room") <* path.static("/messages"))
            .name("Room Messages")
            .description("Recent messages in a specific room")
            .mimeType("text/plain")
            .read { roomName =>
              service.getMessages(roomName, 50).map { msgs =>
                val text = msgs.reverse.map(m => s"[${m.sender}] ${m.content}").mkString("\n")
                ResourceContent.text(s"chat://rooms/$roomName/messages", text, Some("text/plain"))
              }
            }
        )
        // ── prompts ──────────────────────────────────────────────────
        .withContextualPrompt(
          contextualPrompt[ChatSession]
            .name("summarize_chat")
            .description("Summarize recent messages in the current room")
            .generate { (session, _) =>
              for
                room <- session.currentRoom.get
                msgs <- room.traverse(r => session.chat.getMessages(r, 50))
              yield
                val roomName = room.getOrElse("(no room)")
                val history = msgs.getOrElse(Nil).reverse
                  .map(m => s"[${m.sender}] ${m.content}")
                  .mkString("\n")
                List(
                  PromptMessage.user(
                    s"""|Please summarize the recent conversation in room '$roomName'.
                        |
                        |Messages:
                        |$history
                        |
                        |Provide a brief summary of the key topics discussed.
                        |""".stripMargin
                  )
                )
            }
        )
        .enableResourceSubscriptions
        .enableLogging
        .serve
    yield server).useForever
