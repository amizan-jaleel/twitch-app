package com.twitch.frontend

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import tyrian.*
import tyrian.http.*
import scala.scalajs.js.annotation.*

@JSExportTopLevel("TyrianApp")
object Main extends TyrianApp[IO, Msg, Model]:

  def router: Location => Msg =
    _ => Msg.NoOp

  val run: IO[Nothing] => Unit =
    _.unsafeRunAndForget()

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model.init, 
     Cmd.Batch(
       Http.send(Request.get("/api/user"), Msg.fromUserResponse),
       Http.send(Request.get("/api/config"), Msg.fromConfigResponse),
       Http.send(Request.get("/api/followed"), Msg.fromFollowedResponse)
     ))

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    Update.update(model)

  def view(model: Model): Html[Msg] =
    View.view(model)

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None

  def main(args: Array[String]): Unit =
    launch("app")
