package net.andimiller.mcp.core.transport

import cats.effect.kernel.Async
import fs2.Stream
import net.andimiller.mcp.core.protocol.jsonrpc.Message

/** Transport-agnostic abstraction for bidirectional message communication.
  *
  * This allows different transports (stdio, HTTP+SSE, WebSocket, etc.) to be used interchangeably with the same server
  * implementation.
  */
trait MessageChannel[F[_]]:

  /** Stream of incoming messages from the client */
  def incoming: Stream[F, Message]

  /** Send a message to the client */
  def send(message: Message): F[Unit]

  /** Close the channel gracefully */
  def close: F[Unit]

object MessageChannel:

  /** Create a message channel from explicit send/receive functions. Useful for testing and custom transports. */
  def apply[F[_]: Async](
      incomingStream: Stream[F, Message],
      sendFn: Message => F[Unit],
      closeFn: F[Unit]
  ): MessageChannel[F] = new MessageChannel[F]:
    def incoming: Stream[F, Message]    = incomingStream
    def send(message: Message): F[Unit] = sendFn(message)
    def close: F[Unit]                  = closeFn
