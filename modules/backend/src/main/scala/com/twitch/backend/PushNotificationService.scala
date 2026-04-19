package com.twitch.backend

import cats.effect.*
import cats.effect.implicits.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.headers.{Authorization, `Content-Type`}
import io.circe.*
import io.circe.syntax.*
import io.circe.parser.{decode => jsonDecode}
import com.twitch.core.StreamNotification
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.time.Instant

class PushNotificationService(
    client: Client[IO],
    projectId: String,
    serviceAccountKey: ServiceAccountKey,
    parallelSends: Int,
    db: Database,
    cachedToken: Ref[IO, Option[(String, Instant)]]
):

  private val fcmUri = Uri.unsafeFromString(
    s"https://fcm.googleapis.com/v1/projects/$projectId/messages:send"
  )

  private def getAccessToken: IO[String] =
    IO.realTimeInstant.flatMap { now =>
      cachedToken.get.flatMap {
        case Some((token, expiry)) if now.isBefore(expiry.minusSeconds(60)) =>
          IO.pure(token)
        case _ =>
          fetchAccessToken.flatMap { case (token, expiresIn) =>
            val expiry = now.plusSeconds(expiresIn)
            cachedToken.set(Some((token, expiry))).as(token)
          }
      }
    }

  private def fetchAccessToken: IO[(String, Long)] =
    IO.realTimeInstant.flatMap { now =>
      buildJwt(now).flatMap { jwt =>
        val req = Request[IO](method = Method.POST, uri = uri"https://oauth2.googleapis.com/token")
          .withEntity(UrlForm(
            "grant_type" -> "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion" -> jwt
          ))
        client.run(req).use { resp =>
          resp.as[String].flatMap { body =>
            jsonDecode[Json](body) match
              case Right(json) =>
                val token = json.hcursor.get[String]("access_token")
                val expiresIn = json.hcursor.get[Long]("expires_in")
                (token, expiresIn) match
                  case (Right(t), Right(e)) => IO.pure((t, e))
                  case _ => IO.raiseError(new RuntimeException(s"Failed to parse OAuth token response: $body"))
              case Left(err) =>
                IO.raiseError(new RuntimeException(s"Failed to parse OAuth response: $body"))
          }
        }
      }
    }

  private def buildJwt(now: Instant): IO[String] =
    IO.blocking {
      val header = Json.obj(
        "alg" -> "RS256".asJson,
        "typ" -> "JWT".asJson
      )
      val claims = Json.obj(
        "iss" -> serviceAccountKey.clientEmail.asJson,
        "scope" -> "https://www.googleapis.com/auth/firebase.messaging".asJson,
        "aud" -> "https://oauth2.googleapis.com/token".asJson,
        "iat" -> now.getEpochSecond.asJson,
        "exp" -> now.plusSeconds(3600).getEpochSecond.asJson
      )
      val encoder = Base64.getUrlEncoder.withoutPadding
      val headerB64 = encoder.encodeToString(header.noSpaces.getBytes("UTF-8"))
      val claimsB64 = encoder.encodeToString(claims.noSpaces.getBytes("UTF-8"))
      val signingInput = s"$headerB64.$claimsB64"

      val keyBytes = Base64.getDecoder.decode(
        serviceAccountKey.privateKey
          .replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "")
          .replaceAll("\\s", "")
      )
      val keySpec = new PKCS8EncodedKeySpec(keyBytes)
      val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
      val sig = java.security.Signature.getInstance("SHA256withRSA")
      sig.initSign(privateKey)
      sig.update(signingInput.getBytes("UTF-8"))
      val signature = encoder.encodeToString(sig.sign())

      s"$signingInput.$signature"
    }

  def sendToDevice(token: String, notification: StreamNotification): IO[SendResult] =
    getAccessToken.flatMap { accessToken =>
      val payload = Json.obj(
        "message" -> Json.obj(
          "token" -> token.asJson,
          "notification" -> Json.obj(
            "title" -> s"${notification.streamerName} is live!".asJson,
            "body" -> s"Playing ${notification.categoryName}: ${notification.streamTitle}".asJson
          ),
          "data" -> Json.obj(
            "streamerId" -> notification.streamerId.asJson,
            "streamerLogin" -> notification.streamerLogin.asJson,
            "categoryId" -> notification.categoryId.asJson
          )
        )
      )

      val req = Request[IO](method = Method.POST, uri = fcmUri)
        .withEntity(payload.noSpaces)
        .putHeaders(
          Authorization(Credentials.Token(AuthScheme.Bearer, accessToken)),
          `Content-Type`(MediaType.application.json)
        )

      client.run(req).use { resp =>
        if resp.status.isSuccess then IO.pure(SendResult.Success)
        else resp.as[String].flatMap { body =>
          if resp.status.code == 404 || body.contains("UNREGISTERED") then
            db.deletePushSubscription(token).as(SendResult.InvalidToken)
          else
            IO.println(s"FCM error for token ${token.take(10)}...: ${resp.status} $body")
              .as(SendResult.Failed)
        }
      }
    }.handleErrorWith { err =>
      IO.println(s"Push send error: ${err.getMessage}").as(SendResult.Failed)
    }

  def sendBatch(
      subscriptions: List[PushSubscriptionRow],
      notifications: List[StreamNotification]
  ): IO[Unit] =
    val sends = for
      sub <- subscriptions
      notif <- notifications
    yield sendToDevice(sub.deviceToken, notif)
    sends.parTraverseN(parallelSends)(identity).void

enum SendResult:
  case Success, InvalidToken, Failed

case class ServiceAccountKey(
    clientEmail: String,
    privateKey: String,
    projectId: String
)

object ServiceAccountKey:
  private def parse(content: String, source: String): IO[ServiceAccountKey] =
    jsonDecode[Json](content) match
      case Right(json) =>
        val cursor = json.hcursor
        (cursor.get[String]("client_email"),
         cursor.get[String]("private_key"),
         cursor.get[String]("project_id")) match
          case (Right(email), Right(key), Right(pid)) =>
            IO.pure(ServiceAccountKey(email, key, pid))
          case _ =>
            IO.raiseError(new RuntimeException(s"Invalid service account key from $source"))
      case Left(err) =>
        IO.raiseError(new RuntimeException(s"Failed to parse service account key from $source: ${err.getMessage}"))

  def fromJson(json: String): IO[ServiceAccountKey] =
    parse(json, "FCM_SERVICE_ACCOUNT_JSON env var")

  def fromFile(path: String): IO[ServiceAccountKey] =
    IO.blocking {
      val source = scala.io.Source.fromFile(path)
      try source.mkString finally source.close()
    }.flatMap(parse(_, s"file $path"))
