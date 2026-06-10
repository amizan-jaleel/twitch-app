package com.twitch.backend.auth

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

import cats.effect.*
import org.http4s.*

import com.twitch.backend.TwitchApi
import com.twitch.backend.db.SessionRepository
import com.twitch.core.TwitchUser

case class SessionData(
  accessToken: String,
  refreshToken: Option[String],
  sessionId: String,
  tokenExpiresAt: Option[Long],
  user: TwitchUser,
)

class SessionManager(
  sessionRepo: SessionRepository,
  twitchApi: TwitchApi,
  tokenRefreshSkew: FiniteDuration,
  sessionTtl: FiniteDuration,
) {

  private def isExpired(row: com.twitch.backend.db.SessionRow, now: Instant): Boolean =
    SessionManager.isExpired(row.createdAt, row.expiresAt, now, sessionTtl)

  def getSession(req: Request[IO]): IO[Option[SessionData]] =
    req.cookies.find(_.name == "session_id").map(_.content) match {
      case None => IO.pure(None)
      case Some(sid) =>
        IO.realTimeInstant.flatMap { now =>
          sessionRepo
            .getSession(sid)
            .flatMap {
              case Some(row) if isExpired(row, now) =>
                sessionRepo.deleteSession(row.sessionId).as(None)
              case Some(row) =>
                IO.pure(
                  Some(
                    SessionData(
                      accessToken = row.accessToken,
                      refreshToken = row.refreshToken,
                      sessionId = row.sessionId,
                      tokenExpiresAt = row.tokenExpiresAt,
                      user = row.toUser,
                    ),
                  ),
                )
              case None => IO.pure(None)
            }
        }
    }

  def refreshTokenIfNeeded(data: SessionData): IO[SessionData] =
    data.refreshToken match {
      case Some(refreshToken)
          if SessionManager.needsRefresh(data.tokenExpiresAt, Instant.now(), tokenRefreshSkew) =>
        twitchApi
          .refreshToken(refreshToken)
          .flatMap { tokenResp =>
            val expiresAt = Some(Instant.now().plusSeconds(tokenResp.expires_in.toLong))
            sessionRepo
              .updateSessionToken(
                sessionId = data.sessionId,
                accessToken = tokenResp.access_token,
                refreshToken = tokenResp.refresh_token.orElse(data.refreshToken),
                tokenExpiresAt = expiresAt,
              )
              .as(
                data.copy(
                  accessToken = tokenResp.access_token,
                  refreshToken = tokenResp.refresh_token.orElse(data.refreshToken),
                  tokenExpiresAt = expiresAt.map(_.getEpochSecond),
                ),
              )
          }
          .handleErrorWith(_ => IO.pure(data))
      case _ => IO.pure(data)
    }

}

object SessionManager {

  private[auth] def needsRefresh(
    tokenExpiresAt: Option[Long],
    now: Instant,
    skew: FiniteDuration,
  ): Boolean =
    tokenExpiresAt.exists(expiresAt => now.getEpochSecond >= expiresAt - skew.toSeconds)

  private[auth] def isExpired(
    createdAt: Long,
    expiresAt: Option[Long],
    now: Instant,
    fallbackTtl: FiniteDuration,
  ): Boolean = {
    val expiry = expiresAt.getOrElse(createdAt + fallbackTtl.toSeconds)
    now.getEpochSecond >= expiry
  }

}
