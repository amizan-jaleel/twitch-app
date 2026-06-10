package com.twitch.backend

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*

case class OAuthStateClaims(
  expiresAt: Long,
  nonce: String,
) derives Codec.AsObject

class OAuthStateTokenService(secret: String, ttl: FiniteDuration) {

  private val encoder = Base64.getUrlEncoder.withoutPadding
  private val decoder = Base64.getUrlDecoder
  private val key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")

  def createState: IO[String] =
    IO.realTimeInstant.map { now =>
      val claims = OAuthStateClaims(
        expiresAt = now.plusMillis(ttl.toMillis).getEpochSecond,
        nonce = java.util.UUID.randomUUID().toString,
      )
      sign(claims.asJson.noSpaces)
    }

  def verifyState(token: String): Either[String, OAuthStateClaims] =
    verify(token).flatMap { claims =>
      if Instant.now().getEpochSecond >= claims.expiresAt then Left("Expired OAuth state")
      else Right(claims)
    }

  private def sign(payload: String): String = {
    val payloadB64 = encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
    val signatureB64 = encoder.encodeToString(hmac(payloadB64))
    s"$payloadB64.$signatureB64"
  }

  private def verify(token: String): Either[String, OAuthStateClaims] =
    token.split("\\.", 2).toList match {
      case payloadB64 :: signatureB64 :: Nil =>
        val expected = encoder.encodeToString(hmac(payloadB64))
        if !constantTimeEquals(signatureB64, expected) then Left("Invalid OAuth state")
        else {
          try {
            val payload = new String(decoder.decode(payloadB64), StandardCharsets.UTF_8)
            decode[OAuthStateClaims](payload).left.map(_ => "Invalid OAuth state")
          } catch {
            case _: IllegalArgumentException => Left("Invalid OAuth state")
          }
        }
      case _ => Left("Invalid OAuth state")
    }

  private def hmac(payload: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(key)
    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
  }

  private def constantTimeEquals(a: String, b: String): Boolean =
    java
      .security
      .MessageDigest
      .isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8),
      )

}
