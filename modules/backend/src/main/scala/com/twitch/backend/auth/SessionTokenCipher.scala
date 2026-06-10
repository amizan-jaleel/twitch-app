package com.twitch.backend.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

trait SessionTokenCipher {
  def decrypt(value: String): String
  def encrypt(value: String): String
  def enabled: Boolean
  def isEncrypted(value: String): Boolean
}

object SessionTokenCipher {

  private val Prefix = "enc.v1."

  val plaintext: SessionTokenCipher = new SessionTokenCipher {
    def decrypt(value: String): String = value
    def encrypt(value: String): String = value
    def enabled: Boolean = false
    def isEncrypted(value: String): Boolean = false
  }

  def fromSecret(secret: String): SessionTokenCipher =
    if secret.trim.isEmpty then plaintext else new AesGcmSessionTokenCipher(secret)

  private class AesGcmSessionTokenCipher(secret: String) extends SessionTokenCipher {
    private val random = new SecureRandom()

    private val keyBytes = MessageDigest
      .getInstance("SHA-256")
      .digest(s"twitch-app-session-token:$secret".getBytes(StandardCharsets.UTF_8))

    private val key = new SecretKeySpec(keyBytes, "AES")
    private val encoder = Base64.getUrlEncoder.withoutPadding
    private val decoder = Base64.getUrlDecoder

    def enabled: Boolean = true

    def isEncrypted(value: String): Boolean = value.startsWith(Prefix)

    def encrypt(value: String): String = {
      val iv = new Array[Byte](12)
      random.nextBytes(iv)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv))
      val encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))
      s"$Prefix${encoder.encodeToString(iv)}.${encoder.encodeToString(encrypted)}"
    }

    def decrypt(value: String): String =
      if !isEncrypted(value) then value
      else {
        val encoded = value.stripPrefix(Prefix).split("\\.", 2)
        if encoded.length != 2 then throw new IllegalArgumentException("Invalid encrypted token")
        val iv = decoder.decode(encoded(0))
        val encrypted = decoder.decode(encoded(1))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv))
        new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
      }

  }

}
