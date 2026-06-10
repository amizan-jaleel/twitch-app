package com.twitch.backend.auth

import scala.concurrent.duration.*

import java.time.Instant

import munit.FunSuite

class SessionManagerSpec extends FunSuite {

  private val now = Instant.parse("2026-05-09T12:00:00Z")
  private val skew = 5.minutes

  test("needsRefresh returns false when tokenExpiresAt is None") {
    assertEquals(SessionManager.needsRefresh(None, now, skew), false)
  }

  test("needsRefresh returns false when the token expires well outside the skew window") {
    val expiresAt = now.plusSeconds(skew.toSeconds + 60).getEpochSecond
    assertEquals(SessionManager.needsRefresh(Some(expiresAt), now, skew), false)
  }

  test("needsRefresh returns true when the token expires inside the skew window") {
    val expiresAt = now.plusSeconds(skew.toSeconds / 2).getEpochSecond
    assertEquals(SessionManager.needsRefresh(Some(expiresAt), now, skew), true)
  }

  test("needsRefresh returns true when the token has already expired") {
    val expiresAt = now.minusSeconds(60).getEpochSecond
    assertEquals(SessionManager.needsRefresh(Some(expiresAt), now, skew), true)
  }

  test("needsRefresh returns true at the exact skew boundary") {
    val expiresAt = now.plusSeconds(skew.toSeconds).getEpochSecond
    assertEquals(SessionManager.needsRefresh(Some(expiresAt), now, skew), true)
  }

  test("needsRefresh returns false one second outside the skew boundary") {
    val expiresAt = now.plusSeconds(skew.toSeconds + 1).getEpochSecond
    assertEquals(SessionManager.needsRefresh(Some(expiresAt), now, skew), false)
  }

  test("isExpired returns false when explicit session expiry is in the future") {
    val createdAt = now.minusSeconds(3600).getEpochSecond
    val expiresAt = Some(now.plusSeconds(60).getEpochSecond)
    assertEquals(SessionManager.isExpired(createdAt, expiresAt, now, 30.days), false)
  }

  test("isExpired returns true when explicit session expiry has passed") {
    val createdAt = now.minusSeconds(3600).getEpochSecond
    val expiresAt = Some(now.minusSeconds(1).getEpochSecond)
    assertEquals(SessionManager.isExpired(createdAt, expiresAt, now, 30.days), true)
  }

  test("isExpired falls back to createdAt plus ttl for legacy sessions") {
    val createdAt = now.minusSeconds(30.days.toSeconds + 1).getEpochSecond
    assertEquals(SessionManager.isExpired(createdAt, None, now, 30.days), true)
  }

}
