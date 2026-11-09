package com.twitch.backend

import java.net.Socket
import java.nio.charset.StandardCharsets.US_ASCII

import cats.effect.IO
import com.comcast.ip4s.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

// Runs the body limit behind a real Ember server: in-memory route tests can't
// show which status the server actually puts on the wire.
class RequestBodyLimitSpec extends CatsEffectSuite {

  private val jsonApp: HttpApp[IO] = HttpRoutes
    .of[IO] { case req @ POST -> Root / "echo" => req.as[Json].flatMap(_ => Ok("ok")) }
    .orNotFound

  private def statusLine(request: String): IO[String] =
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"127.0.0.1")
      .withPort(port"0")
      .withHttpApp(AppWiring.limitRequestBodies(jsonApp))
      .build
      .use { server =>
        IO.blocking {
          val socket = new Socket("127.0.0.1", server.address.getPort)
          try {
            socket.setSoTimeout(5000)
            socket.getOutputStream.write(request.getBytes(US_ASCII))
            socket.getOutputStream.flush()
            new String(socket.getInputStream.readAllBytes(), US_ASCII).takeWhile(_ != '\r')
          } finally socket.close()
        }
      }

  private def jsonOfSize(bytes: Int): String = {
    val prefix = "{\"category\":\""
    val suffix = "\"}"
    prefix + ("x" * (bytes - prefix.length - suffix.length)) + suffix
  }

  private def withContentLength(body: String): String =
    "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
      s"Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

  private def chunked(body: String): String =
    "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
      "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
      s"${body.length.toHexString}\r\n$body\r\n0\r\n\r\n"

  private val oneOverLimit = AppWiring.MaxRequestBodyBytes.toInt + 1

  test("Oversized Content-Length body returns 413") {
    statusLine(withContentLength(jsonOfSize(oneOverLimit)))
      .map(assertEquals(_, "HTTP/1.1 413 Payload Too Large"))
  }

  test("Oversized chunked body returns 413") {
    statusLine(chunked(jsonOfSize(oneOverLimit)))
      .map(assertEquals(_, "HTTP/1.1 413 Payload Too Large"))
  }

  test("Body exactly at the limit is accepted") {
    statusLine(withContentLength(jsonOfSize(AppWiring.MaxRequestBodyBytes.toInt)))
      .map(assertEquals(_, "HTTP/1.1 200 OK"))
  }

}
