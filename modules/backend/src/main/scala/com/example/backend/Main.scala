package com.example.backend

import cats.effect.*
import com.comcast.ip4s.*
import org.http4s.ember.server.*
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.dsl.io.*
import org.http4s.HttpRoutes
import com.example.core.Ping
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.server.middleware.CORS

object Main extends IOApp.Simple:

  private val helloWorldService = HttpRoutes.of[IO] {
    case GET -> Root / "hello" / name =>
      Ok(s"Hello, $name.")
    case GET -> Root / "ping" =>
      IO.println("Received ping request, responding with pong") *> Ok(Ping("pong"))
  }

  def run: IO[Unit] =
    val host = host"0.0.0.0"
    val port = port"8080"
    
    val httpApp = Router("/" -> helloWorldService).orNotFound
    val corsApp = CORS.policy.withAllowOriginAll(httpApp)
    
    IO.println(s"Server started at http://$host:$port") *>
      EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(corsApp)
      .build
      .useForever
