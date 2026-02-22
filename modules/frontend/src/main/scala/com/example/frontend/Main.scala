package com.example.frontend

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import tyrian.Html.*
import tyrian.*
import tyrian.http.*
import scala.scalajs.js.annotation.*
import com.example.core.Ping
import io.circe.parser.decode

@JSExportTopLevel("TyrianApp")
object Main extends TyrianApp[IO, Msg, Model]:

  def router: Location => Msg =
    _ => Msg.NoOp

  val run: IO[Nothing] => Unit =
    _.unsafeRunAndForget()

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model("Click the button to ping the server", None), Cmd.None)

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.SendPing =>
      (model.copy(status = Some("Sending ping...")), Http.send(Request.get("http://localhost:8080/ping"), Msg.fromHttpResponse))
    case Msg.GotPing(ping) =>
      (model.copy(status = Some(s"Server said: ${ping.message}")), Cmd.None)
    case Msg.PingError(err) =>
      (model.copy(status = Some(s"Error: $err")), Cmd.None)
    case Msg.NoOp =>
      (model, Cmd.None)

  def view(model: Model): Html[Msg] =
    div(
      h1("Tyrian <-> Http4s"),
      p(model.message),
      button(onClick(Msg.SendPing))("Send Ping to Backend"),
      model.status.map(s => p(style("font-weight", "bold"))(s)).getOrElse(div())
    )

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None

  def main(args: Array[String]): Unit =
    launch("app")

case class Model(message: String, status: Option[String])

enum Msg:
  case SendPing
  case GotPing(ping: Ping)
  case PingError(error: String)
  case NoOp

object Msg:
  def fromHttpResponse: Decoder[Msg] = Decoder(
    response =>
      response.status match
        case Status(code, _) if code >= 200 && code < 300 =>
          decode[Ping](response.body) match
            case Right(ping) => Msg.GotPing(ping)
            case Left(err)   => Msg.PingError(s"JSON Decoding error: ${err.getMessage}")
        case Status(code, msg) =>
          Msg.PingError(s"Server returned $code: $msg"),
    error => Msg.PingError(s"Network error: ${error.toString}")
  )
