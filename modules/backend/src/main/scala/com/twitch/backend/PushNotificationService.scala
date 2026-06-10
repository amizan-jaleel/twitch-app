package com.twitch.backend

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Mutex
import io.circe.*
import io.circe.parser.{decode => jsonDecode}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.{`Content-Type`, Authorization}
import org.http4s.implicits.*

import com.twitch.backend.db.{PushSubscriptionRepository, PushSubscriptionRow}
import com.twitch.core.StreamNotification

class PushNotificationService(
  client: Client[IO],
  parallelSends: Int,
  projectId: String,
  pushActionTokens: PushActionTokenService,
  pushRepo: PushSubscriptionRepository,
  serviceAccountKey: ServiceAccountKey,
  tokenCache: Ref[IO, Option[(String, Instant)]],
  tokenMutex: Mutex[IO],
) extends PushService {

  private val fcmUri = Uri.unsafeFromString(
    s"https://fcm.googleapis.com/v1/projects/$projectId/messages:send",
  )

  private def getAccessToken: IO[String] =
    tokenMutex.lock.surround {
      IO.realTimeInstant.flatMap { now =>
        tokenCache.get.flatMap {
          case Some((token, expiry)) if now.isBefore(expiry.minusSeconds(60)) =>
            IO.pure(token)
          case _ =>
            fetchAccessToken.flatMap {
              case (token, expiresIn) =>
                val expiry = now.plusSeconds(expiresIn)
                tokenCache.set(Some((token, expiry))).as(token)
            }
        }
      }
    }

  private def fetchAccessToken: IO[(String, Long)] =
    IO.realTimeInstant.flatMap { now =>
      val jwt = buildJwt(now)
      val req = Request[IO](method = Method.POST, uri = uri"https://oauth2.googleapis.com/token")
        .withEntity(
          UrlForm(
            "grant_type" -> "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion" -> jwt,
          ),
        )
      client.run(req).use { resp =>
        resp.as[String].flatMap { body =>
          jsonDecode[Json](body) match {
            case Right(json) =>
              val token = json.hcursor.get[String]("access_token")
              val expiresIn = json.hcursor.get[Long]("expires_in")
              (token, expiresIn) match {
                case (Right(t), Right(e)) => IO.pure((t, e))
                case _ =>
                  IO.raiseError(
                    new RuntimeException(s"Failed to parse OAuth token response: $body"),
                  )
              }
            case Left(err) =>
              IO.raiseError(new RuntimeException(s"Failed to parse OAuth response: $body"))
          }
        }
      }
    }

  private def buildJwt(now: Instant): String = {
    val header = Json.obj(
      "alg" -> "RS256".asJson,
      "typ" -> "JWT".asJson,
    )
    val claims = Json.obj(
      "iss" -> serviceAccountKey.clientEmail.asJson,
      "scope" -> "https://www.googleapis.com/auth/firebase.messaging".asJson,
      "aud" -> "https://oauth2.googleapis.com/token".asJson,
      "iat" -> now.getEpochSecond.asJson,
      "exp" -> now.plusSeconds(3600).getEpochSecond.asJson,
    )
    val encoder = Base64.getUrlEncoder.withoutPadding
    val headerB64 = encoder.encodeToString(header.noSpaces.getBytes("UTF-8"))
    val claimsB64 = encoder.encodeToString(claims.noSpaces.getBytes("UTF-8"))
    val signingInput = s"$headerB64.$claimsB64"

    val keyBytes = Base64
      .getDecoder
      .decode(
        serviceAccountKey
          .privateKey
          .replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "")
          .replaceAll("\\s", ""),
      )
    val keySpec = new PKCS8EncodedKeySpec(keyBytes)
    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    val sig = java.security.Signature.getInstance("SHA256withRSA")
    sig.initSign(privateKey)
    sig.update(signingInput.getBytes("UTF-8"))
    val signature = encoder.encodeToString(sig.sign())

    s"$signingInput.$signature"
  }

  def sendToDevice(
    subscription: PushSubscriptionRow,
    notification: StreamNotification,
  ): IO[SendResult] =
    getAccessToken
      .flatMap { accessToken =>
        pushActionTokens.createIgnoreStreamerToken(subscription.userId, notification).flatMap {
          actionToken =>
            val payload =
              PushNotificationService.messagePayload(subscription, notification, actionToken)

            val req = Request[IO](method = Method.POST, uri = fcmUri)
              .withEntity(payload.noSpaces)
              .putHeaders(
                Authorization(Credentials.Token(AuthScheme.Bearer, accessToken)),
                `Content-Type`(MediaType.application.json),
              )

            client.run(req).use { resp =>
              if resp.status.isSuccess then IO.pure(SendResult.Success)
              else
                resp.as[String].flatMap { body =>
                  if resp.status.code == 404 || body.contains("UNREGISTERED") then
                    pushRepo
                      .deletePushSubscription(subscription.userId, subscription.deviceToken)
                      .as(SendResult.InvalidToken)
                  else
                    IO.println(
                      s"FCM error for token ${subscription.deviceToken.take(10)}...: ${resp.status} $body",
                    ).as(SendResult.Failed)
                }
            }
        }
      }
      .handleErrorWith { err =>
        IO.println(s"Push send error: ${err.getMessage}").as(SendResult.Failed)
      }

  def sendBatch(
    subscriptions: List[PushSubscriptionRow],
    notifications: List[StreamNotification],
  ): IO[Unit] = {
    val sends = for {
      sub <- subscriptions
      notif <- notifications
    } yield sendToDevice(sub, notif)
    sends.parTraverseN(parallelSends)(identity).void
  }

}

enum SendResult {
  case Success, InvalidToken, Failed
}

case class ServiceAccountKey(
  clientEmail: String,
  privateKey: String,
  projectId: String,
)

object PushNotificationService {

  private[backend] def messagePayload(
    subscription: PushSubscriptionRow,
    notification: StreamNotification,
    actionToken: String,
  ): Json = {
    val title = s"${notification.streamerName} is live!"
    val body = s"Playing ${notification.categoryName}: ${notification.streamTitle}"
    val data = Json.obj(
      "title" -> title.asJson,
      "body" -> body.asJson,
      "streamerId" -> notification.streamerId.asJson,
      "streamerLogin" -> notification.streamerLogin.asJson,
      "streamerName" -> notification.streamerName.asJson,
      "categoryId" -> notification.categoryId.asJson,
      "actionToken" -> actionToken.asJson,
    )

    val baseMessage = Json.obj(
      "token" -> subscription.deviceToken.asJson,
      "data" -> data,
    )

    val message =
      if subscription.platform.equalsIgnoreCase("ios") then
        baseMessage.deepMerge(
          Json.obj(
            "notification" -> Json.obj(
              "title" -> title.asJson,
              "body" -> body.asJson,
            ),
            "apns" -> Json.obj(
              "headers" -> Json.obj(
                "apns-push-type" -> "alert".asJson,
                "apns-priority" -> "10".asJson,
              ),
              "payload" -> Json.obj(
                "aps" -> Json.obj(
                  "alert" -> Json.obj(
                    "title" -> title.asJson,
                    "body" -> body.asJson,
                  ),
                  "sound" -> "default".asJson,
                  "category" -> "STREAM_LIVE".asJson,
                ),
              ),
            ),
          ),
        )
      else baseMessage

    Json.obj("message" -> message)
  }

}

object ServiceAccountKey {

  private def parse(content: String, source: String): IO[ServiceAccountKey] =
    jsonDecode[Json](content) match {
      case Right(json) =>
        val cursor = json.hcursor
        (
          cursor.get[String]("client_email"),
          cursor.get[String]("private_key"),
          cursor.get[String]("project_id"),
        ) match {
          case (Right(email), Right(key), Right(pid)) =>
            IO.pure(ServiceAccountKey(clientEmail = email, privateKey = key, projectId = pid))
          case _ =>
            IO.raiseError(new RuntimeException(s"Invalid service account key from $source"))
        }
      case Left(err) =>
        IO.raiseError(
          new RuntimeException(
            s"Failed to parse service account key from $source: ${err.getMessage}",
          ),
        )
    }

  def fromJson(json: String): IO[ServiceAccountKey] =
    parse(json, "FCM_SERVICE_ACCOUNT_JSON env var")

  def fromFile(path: String): IO[ServiceAccountKey] =
    IO.blocking(scala.io.Source.fromFile(path))
      .bracket(source => IO.blocking(source.mkString))(source => IO.blocking(source.close()))
      .flatMap(parse(_, s"file $path"))

}
