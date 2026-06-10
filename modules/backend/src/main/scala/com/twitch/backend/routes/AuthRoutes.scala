package com.twitch.backend.routes

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

import cats.effect.*
import org.http4s.*
import org.http4s.SameSite
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.implicits.*

import com.twitch.backend.{EmailNotifier, OAuthStateTokenService, TwitchApi}
import com.twitch.backend.db.{SessionRepository, UserRepository}
import com.twitch.core.TwitchUser

class AuthRoutes(
  clientId: String,
  emailService: Option[EmailNotifier],
  oauthStateTokens: OAuthStateTokenService,
  redirectUri: String,
  sessionRepo: SessionRepository,
  sessionTtl: FiniteDuration,
  twitchApi: TwitchApi,
  userRepo: UserRepository,
) {

  private val secureCookies = redirectUri.startsWith("https")
  private val oauthStateCookie = "oauth_state"

  private val expiredOAuthStateCookie = ResponseCookie(
    oauthStateCookie,
    "",
    expires = Some(HttpDate.Epoch),
    path = Some("/auth"),
    httpOnly = true,
    secure = secureCookies,
    sameSite = Some(SameSite.Lax),
  )

  private class InvalidOAuthStateException extends RuntimeException("Invalid OAuth state parameter")

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def validateOAuthState(req: Request[IO], state: String): Boolean = {
    val cookieState = req.cookies.find(_.name == oauthStateCookie).map(_.content)
    cookieState.contains(state) && oauthStateTokens.verifyState(state).isRight
  }

  private def sendWelcomeEmailIfNeeded(user: TwitchUser): IO[Unit] =
    (user.email, emailService) match {
      case (Some(email), Some(es)) =>
        es.sendWelcomeEmail(email, user.display_name)
          .flatMap(_ => userRepo.markWelcomeEmailSent(user.id))
          .handleErrorWith(err =>
            IO.println(s"Failed to send welcome email to ${user.id}: ${err.getMessage}"),
          )
          .start
          .void
      case _ =>
        IO.println(
          s"Skipping welcome email for ${user.id} (no email or email service not configured)",
        )
    }

  private object CodeQueryParamMatcher extends QueryParamDecoderMatcher[String]("code")
  private object StateQueryParamMatcher extends QueryParamDecoderMatcher[String]("state")

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "auth" / "login" =>
      oauthStateTokens.createState.flatMap { state =>
        val authorizeUri =
          s"https://id.twitch.tv/oauth2/authorize?client_id=${urlEncode(clientId)}&redirect_uri=${urlEncode(redirectUri)}&response_type=code&scope=${urlEncode("user:read:email")}&state=${urlEncode(state)}"
        Found(Location(Uri.unsafeFromString(authorizeUri))).map(
          _.addCookie(
            ResponseCookie(
              oauthStateCookie,
              state,
              path = Some("/auth"),
              httpOnly = true,
              secure = secureCookies,
              sameSite = Some(SameSite.Lax),
            ),
          ),
        )
      }

    case req @ GET -> Root / "auth" / "callback" :? CodeQueryParamMatcher(
          code,
        ) +& StateQueryParamMatcher(
          state,
        ) =>
      val flow = for {
        validState <- IO.delay(validateOAuthState(req, state))
        _ <- IO.raiseUnless(validState)(new InvalidOAuthStateException)
        _ <- IO.println("Received auth callback")
        tokenResponse <- twitchApi.exchangeCode(code, redirectUri)
        _ <- IO.println("Token exchange successful")
        user <- twitchApi.getUser(tokenResponse.access_token)
        _ <- IO.println(s"Found user: ${user.display_name}")
        existingUser <- userRepo.findUser(user.id)
        _ <- existingUser match {
          case None =>
            userRepo.insertUser(user.id, user.login, user.display_name, user.email) *>
              sendWelcomeEmailIfNeeded(user)
          case Some(existing) =>
            userRepo.updateLastLogin(user.id, user.login, user.display_name, user.email) *>
              (if !existing.welcomeEmailSent then sendWelcomeEmailIfNeeded(user) else IO.unit)
        }
        now <- IO.realTimeInstant
        sessionId = UUID.randomUUID().toString
        tokenExpiresAt = Some(now.plusSeconds(tokenResponse.expires_in.toLong))
        sessionExpiresAt = now.plusMillis(sessionTtl.toMillis)
        _ <- sessionRepo.createSession(
          sessionId,
          user,
          tokenResponse.access_token,
          tokenResponse.refresh_token,
          tokenExpiresAt,
          sessionExpiresAt = sessionExpiresAt,
          createdAt = now,
        )
        res <- Found(Location(uri"/")).map(
          _.addCookie(
            ResponseCookie(
              "session_id",
              sessionId,
              path = Some("/"),
              httpOnly = true,
              secure = secureCookies,
              sameSite = Some(SameSite.Lax),
            ),
          ).addCookie(expiredOAuthStateCookie),
        )
      } yield res

      flow.handleErrorWith { err =>
        err match {
          case _: InvalidOAuthStateException => BadRequest("Invalid OAuth state parameter")
          case _ =>
            IO.println(s"Auth flow failed: ${err.getMessage}") *>
              InternalServerError("Auth flow failed. Check server logs.")
        }
      }
  }

}
