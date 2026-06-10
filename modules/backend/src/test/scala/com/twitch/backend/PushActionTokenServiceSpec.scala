package com.twitch.backend

import scala.concurrent.duration.*

import munit.CatsEffectSuite

import com.twitch.core.StreamNotification

class PushActionTokenServiceSpec extends CatsEffectSuite {

  private val notification = StreamNotification(
    categoryId = "cat1",
    categoryName = "Test Game",
    streamerId = "streamer-1",
    streamerLogin = "streamerlogin",
    streamerName = "Streamer Name",
    streamTitle = "Live",
    tags = List("English"),
    thumbnailUrl = "thumb.jpg",
    viewerCount = 100,
  )

  test("createIgnoreStreamerToken signs scoped user and streamer claims") {
    val service = new PushActionTokenService("test-secret", 30.minutes)
    for token <- service.createIgnoreStreamerToken("user-1", notification)
    yield {
      val claims = service.verifyIgnoreStreamerToken(token)
      assertEquals(claims.map(_.userId), Right("user-1"))
      assertEquals(claims.map(_.streamerId), Right("streamer-1"))
      assertEquals(claims.map(_.action), Right(PushActionTokenService.IgnoreStreamerAction))
    }
  }

  test("verifyIgnoreStreamerToken rejects tampered signatures") {
    val service = new PushActionTokenService("test-secret", 30.minutes)
    for token <- service.createIgnoreStreamerToken("user-1", notification)
    yield {
      val replacement = if token.last == 'A' then 'B' else 'A'
      val tampered = token.dropRight(1) + replacement
      assertEquals(service.verifyIgnoreStreamerToken(tampered).isLeft, true)
    }
  }

  test("verifyIgnoreStreamerToken rejects expired tokens") {
    val service = new PushActionTokenService("test-secret", (-1).seconds)
    for token <- service.createIgnoreStreamerToken("user-1", notification)
    yield assertEquals(service.verifyIgnoreStreamerToken(token).isLeft, true)
  }

  test("verifyIgnoreStreamerToken rejects tokens signed with another secret") {
    val issuer = new PushActionTokenService("issuer-secret", 30.minutes)
    val verifier = new PushActionTokenService("verifier-secret", 30.minutes)
    for token <- issuer.createIgnoreStreamerToken("user-1", notification)
    yield assertEquals(verifier.verifyIgnoreStreamerToken(token).isLeft, true)
  }

}
