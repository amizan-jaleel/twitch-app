package com.twitch.backend.auth

import munit.FunSuite

class SessionTokenCipherSpec extends FunSuite {

  test("fromSecret returns plaintext cipher for blank secrets") {
    val cipher = SessionTokenCipher.fromSecret("   ")

    assertEquals(cipher.enabled, false)
    assertEquals(cipher.encrypt("token"), "token")
    assertEquals(cipher.decrypt("token"), "token")
    assertEquals(cipher.isEncrypted("token"), false)
  }

  test("AES-GCM cipher round-trips tokens and uses a fresh IV") {
    val cipher = SessionTokenCipher.fromSecret("session-token-secret")

    val encrypted1 = cipher.encrypt("token")
    val encrypted2 = cipher.encrypt("token")

    assertEquals(cipher.enabled, true)
    assert(cipher.isEncrypted(encrypted1))
    assert(cipher.isEncrypted(encrypted2))
    assertNotEquals(encrypted1, encrypted2)
    assertEquals(cipher.decrypt(encrypted1), "token")
    assertEquals(cipher.decrypt(encrypted2), "token")
  }

  test("AES-GCM cipher rejects ciphertext encrypted with another secret") {
    val encrypted = SessionTokenCipher.fromSecret("old-secret").encrypt("token")
    val newCipher = SessionTokenCipher.fromSecret("new-secret")

    intercept[Exception] {
      newCipher.decrypt(encrypted)
    }
  }

}
