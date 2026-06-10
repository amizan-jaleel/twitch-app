package com.twitch.backend

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

class SecurityHeadersSpec extends CatsEffectSuite {

  private def headerValue(resp: Response[IO], name: String): Option[String] =
    resp.headers.get(CIString(name)).map(_.head.value)

  private val baseApp =
    HttpRoutes
      .of[IO] { case GET -> Root => Ok("ok") }
      .orNotFound

  test("adds browser security headers without wildcard CORS") {
    for resp <- SecurityHeaders(baseApp, hsts = false).run(Request[IO](Method.GET, uri"/"))
    yield {
      assertEquals(headerValue(resp, "X-Content-Type-Options"), Some("nosniff"))
      assertEquals(headerValue(resp, "X-Frame-Options"), Some("DENY"))
      assertEquals(headerValue(resp, "Referrer-Policy"), Some("no-referrer"))
      assertEquals(headerValue(resp, "Access-Control-Allow-Origin"), None)
      assert(
        headerValue(resp, "Content-Security-Policy").exists(_.contains("frame-ancestors 'none'")),
      )
      assert(
        headerValue(resp, "Content-Security-Policy").exists(_.contains("connect-src 'self' data:")),
      )
      assertEquals(headerValue(resp, "Strict-Transport-Security"), None)
    }
  }

  test("adds HSTS only when enabled by HTTPS deployment config") {
    for resp <- SecurityHeaders(baseApp, hsts = true).run(Request[IO](Method.GET, uri"/"))
    yield assertEquals(
      headerValue(resp, "Strict-Transport-Security"),
      Some("max-age=31536000; includeSubDomains"),
    )
  }

}
