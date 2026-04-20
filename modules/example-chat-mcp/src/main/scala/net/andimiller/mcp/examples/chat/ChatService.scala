package net.andimiller.mcp.examples.chat

import cats.effect.IO
import cats.syntax.all.*
import dev.profunktor.redis4cats.RedisCommands
import io.circe.{Decoder, Encoder}
import io.circe.parser.decode
import io.circe.syntax.*

case class ChatMessage(
  sender: String,
  content: String,
  timestamp: Long
) derives Encoder.AsObject, Decoder

case class ChatRoom(
  name: String,
  createdBy: String
) derives Encoder.AsObject, Decoder

class ChatService(redis: RedisCommands[IO, String, String]):

  private def roomsKey        = "chat:rooms"
  private def roomKey(n: String)    = s"chat:room:$n"
  private def membersKey(n: String) = s"chat:room:$n:members"
  private def messagesKey(n: String) = s"chat:messages:$n"

  def createRoom(name: String, creator: String): IO[ChatRoom] =
    val room = ChatRoom(name, creator)
    redis.sAdd(roomsKey, name) *>
      redis.set(roomKey(name), room.asJson.noSpaces) *>
      redis.sAdd(membersKey(name), creator).as(room)

  def listRooms: IO[List[String]] =
    redis.sMembers(roomsKey).map(_.toList.sorted)

  def joinRoom(room: String, username: String): IO[Unit] =
    redis.sAdd(membersKey(room), username).void

  def leaveRoom(room: String, username: String): IO[Unit] =
    redis.sRem(membersKey(room), username).void

  def getMembers(room: String): IO[Set[String]] =
    redis.sMembers(membersKey(room))

  def sendMessage(room: String, sender: String, content: String): IO[ChatMessage] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      val msg = ChatMessage(sender, content, now)
      redis.lPush(messagesKey(room), msg.asJson.noSpaces).as(msg)
    }

  def getMessages(room: String, count: Int = 50): IO[List[ChatMessage]] =
    redis.lRange(messagesKey(room), 0, count.toLong - 1).map { strings =>
      strings.flatMap(s => decode[ChatMessage](s).toOption)
    }
