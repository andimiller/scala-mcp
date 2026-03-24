package net.andimiller.mcp.explorer

import cats.effect.IO
import tyrian.*

object Main extends TyrianIOApp[Msg, Model]:

  def main(args: Array[String]): Unit = launch("main")

  def router: Location => Msg = Routing.none(Msg.NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) = (Model.init, Cmd.None)

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = Update.update(model)

  def view(model: Model): Html[Msg] = Views.view(model)

  def subscriptions(model: Model): Sub[IO, Msg] = Sub.None
