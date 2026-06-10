package com.twitch.backend

import scala.concurrent.duration.FiniteDuration

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*

import com.twitch.core.StreamNotification

case class PushActionClaims(
  action: String,
  expiresAt: Long,
  nonce: String,
  streamerId: String,
  streamerLogin: String,
  streamerName: String,
  userId: String,
) derives Codec.AsObject

class PushActionTokenService(secret: String, ttl: FiniteDuration) {

  private val encoder = Base64.getUrlEncoder.withoutPadding
  private val decoder = Base64.getUrlDecoder
  private val key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")

  def createIgnoreStreamerToken(userId: String, notification: StreamNotification): IO[String] =
    IO.realTimeInstant.map { now =>
      val claims = PushActionClaims(
        action = PushActionTokenService.IgnoreStreamerAction,
        expiresAt = now.plusMillis(ttl.toMillis).getEpochSecond,
        // Entropy only: action tokens are intentionally replayable until expiry because the
        // ignore-streamer action is idempotent.
        nonce = java.util.UUID.randomUUID().toString,
        streamerId = notification.streamerId,
        streamerLogin = notification.streamerLogin,
        streamerName = notification.streamerName,
        userId = userId,
      )
      sign(claims.asJson.noSpaces)
    }

  def verifyIgnoreStreamerToken(token: String): Either[String, PushActionClaims] =
    verify(token).flatMap { claims =>
      if claims.action != PushActionTokenService.IgnoreStreamerAction then Left("Invalid action")
      else if Instant.now().getEpochSecond >= claims.expiresAt then Left("Expired action token")
      else Right(claims)
    }

  private def sign(payload: String): String = {
    val payloadB64 = encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
    val signatureB64 = encoder.encodeToString(hmac(payloadB64))
    s"$payloadB64.$signatureB64"
  }

  private def verify(token: String): Either[String, PushActionClaims] =
    token.split("\\.", 2).toList match {
      case payloadB64 :: signatureB64 :: Nil =>
        val expected = encoder.encodeToString(hmac(payloadB64))
        if !constantTimeEquals(signatureB64, expected) then Left("Invalid action token")
        else {
          try {
            val payload = new String(decoder.decode(payloadB64), StandardCharsets.UTF_8)
            decode[PushActionClaims](payload).left.map(_ => "Invalid action token")
          } catch {
            case _: IllegalArgumentException => Left("Invalid action token")
          }
        }
      case _ => Left("Invalid action token")
    }

  private def hmac(payload: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(key)
    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
  }

  private def constantTimeEquals(a: String, b: String): Boolean = {
    val aBytes = a.getBytes(StandardCharsets.UTF_8)
    val bBytes = b.getBytes(StandardCharsets.UTF_8)
    java.security.MessageDigest.isEqual(aBytes, bBytes)
  }

}

object PushActionTokenService {

  val IgnoreStreamerAction = "ignore-streamer"

}
