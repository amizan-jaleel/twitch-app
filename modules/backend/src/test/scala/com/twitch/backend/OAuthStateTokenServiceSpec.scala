package com.twitch.backend

import scala.concurrent.duration.*

import munit.CatsEffectSuite

class OAuthStateTokenServiceSpec extends CatsEffectSuite {

  test("createState signs expiring state claims") {
    val service = new OAuthStateTokenService("oauth-state-secret", 10.minutes)

    for state <- service.createState
    yield {
      val claims = service.verifyState(state)
      assert(claims.isRight)
      assert(claims.exists(_.nonce.nonEmpty))
    }
  }

  test("verifyState rejects tampered state tokens") {
    val service = new OAuthStateTokenService("oauth-state-secret", 10.minutes)

    for state <- service.createState
    yield {
      val replacement = if state.last == 'A' then 'B' else 'A'
      val tampered = state.dropRight(1) + replacement
      assertEquals(service.verifyState(tampered).isLeft, true)
    }
  }

  test("verifyState rejects expired state tokens") {
    val service = new OAuthStateTokenService("oauth-state-secret", (-1).seconds)

    for state <- service.createState
    yield assertEquals(service.verifyState(state).isLeft, true)
  }

  test("verifyState rejects state tokens signed with another secret") {
    val issuer = new OAuthStateTokenService("issuer-secret", 10.minutes)
    val verifier = new OAuthStateTokenService("verifier-secret", 10.minutes)

    for state <- issuer.createState
    yield assertEquals(verifier.verifyState(state).isLeft, true)
  }

}
