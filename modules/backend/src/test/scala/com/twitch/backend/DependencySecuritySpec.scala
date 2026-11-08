package com.twitch.backend

import java.net.Socket
import java.nio.charset.StandardCharsets.US_ASCII

import cats.effect.{IO, Ref}
import com.comcast.ip4s.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

class DependencySecuritySpec extends CatsEffectSuite {

  private def exchange(port: Int, request: String): IO[String] = IO.blocking {
    val socket = new Socket("127.0.0.1", port)
    try {
      socket.setSoTimeout(5000)
      socket.getOutputStream.write(request.getBytes(US_ASCII))
      socket.getOutputStream.flush()
      new String(socket.getInputStream.readAllBytes(), US_ASCII)
    } finally socket.close()
  }

  // Exercise the real Ember wire parser; in-memory routes bypass HTTP framing checks.
  private def rejectsFraming(headers: String): IO[Unit] =
    for {
      seen <- Ref.of[IO, List[String]](Nil)
      app = HttpRoutes
        .of[IO] {
          case req =>
            req.body.compile.drain *> seen.update(_ :+ req.uri.path.renderString) *> Ok("ok")
        }
        .orNotFound
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"127.0.0.1")
        .withPort(port"0")
        .withHttpApp(app)
        .build
        .use { server =>
          for {
            control <- exchange(
              server.address.getPort,
              "GET /control HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
            )
            _ = assert(control.startsWith("HTTP/1.1 200"))
            response <- exchange(
              server.address.getPort,
              s"POST /probe HTTP/1.1\r\nHost: localhost\r\n$headers\r\n\r\n" +
                "GET /smuggled HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
            )
            _ = assert(response.startsWith("HTTP/1.1 400"), response)
            paths <- seen.get
            _ = assertEquals(paths, List("/control"))
          } yield ()
        }
    } yield ()

  test("Ember rejects Transfer-Encoding combined with Content-Length") {
    rejectsFraming("Transfer-Encoding: chunked\r\nContent-Length: 0")
  }

  test("Ember rejects conflicting Content-Length values") {
    rejectsFraming("Content-Length: 0\r\nContent-Length: 1")
  }

  test("Ember rejects a bare LF hiding Content-Length inside another header") {
    rejectsFraming("X-Probe: value\nContent-Length: 0")
  }

  test("JSON entity decoding rejects excessive nesting with a recoverable failure") {
    val nested = "[" * 5000 + "0" + "]" * 5000
    Request[IO](Method.POST)
      .withEntity(nested)
      .putHeaders(headers.`Content-Type`(MediaType.application.json))
      .attemptAs[Json]
      .value
      .map(result => assert(result.isLeft, "Deep JSON must be rejected before exhausting the heap"))
  }

  test("JSON entity decoding still accepts ordinary request bodies") {
    val json = Json.obj("tag" -> Json.fromString("english"))
    Request[IO](Method.POST).withEntity(json).as[Json].map(result => assertEquals(result, json))
  }

}
