package com.twitch.backend.db

import java.time.Instant

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import com.twitch.backend.auth.SessionTokenCipher
import com.twitch.core.TwitchUser

class SessionRepository(
  xa: Transactor[IO],
  tokenCipher: SessionTokenCipher = SessionTokenCipher.plaintext,
) {

  def createSession(
    sessionId: String,
    user: TwitchUser,
    accessToken: String,
    refreshToken: Option[String],
    tokenExpiresAt: Option[Instant],
    sessionExpiresAt: Instant,
    createdAt: Instant,
  ): IO[Unit] = {
    val expiresAt = tokenExpiresAt.map(_.getEpochSecond)
    val sessionExpiresAtEpoch = sessionExpiresAt.getEpochSecond
    for {
      encryptedAccess <- IO.delay(tokenCipher.encrypt(accessToken))
      encryptedRefresh <- IO.delay(refreshToken.map(tokenCipher.encrypt))
      createdAtEpoch = createdAt.getEpochSecond
      _ <- sql"""
        INSERT INTO sessions (session_id, user_id, user_login, display_name, profile_image_url, access_token, refresh_token, token_expires_at, created_at, expires_at)
        VALUES ($sessionId, ${user.id}, ${user.login}, ${user.display_name}, ${user.profile_image_url}, $encryptedAccess, $encryptedRefresh, $expiresAt, $createdAtEpoch, $sessionExpiresAtEpoch)
      """.update.run.transact(xa)
    } yield ()
  }

  def getSession(sessionId: String): IO[Option[SessionRow]] =
    sql"""
      SELECT session_id, user_id, user_login, display_name, profile_image_url, access_token, refresh_token, token_expires_at, created_at, expires_at
      FROM sessions WHERE session_id = $sessionId
    """.query[SessionRow].option.transact(xa).flatMap {
      case Some(row) =>
        decryptRow(row)
          .map(Some(_))
          .handleErrorWith(_ => deleteSession(sessionId).as(None))
      case None => IO.pure(None)
    }

  def updateSessionToken(
    sessionId: String,
    accessToken: String,
    refreshToken: Option[String],
    tokenExpiresAt: Option[Instant],
  ): IO[Unit] = {
    val expiresAt = tokenExpiresAt.map(_.getEpochSecond)
    for {
      encryptedAccess <- IO.delay(tokenCipher.encrypt(accessToken))
      encryptedRefresh <- IO.delay(refreshToken.map(tokenCipher.encrypt))
      _ <- sql"""
        UPDATE sessions SET access_token = $encryptedAccess, refresh_token = $encryptedRefresh, token_expires_at = $expiresAt
        WHERE session_id = $sessionId
      """.update.run.transact(xa)
    } yield ()
  }

  def deleteSession(sessionId: String): IO[Unit] =
    sql"DELETE FROM sessions WHERE session_id = $sessionId"
      .update
      .run
      .transact(xa)
      .void

  def encryptPlaintextTokens: IO[Unit] =
    if !tokenCipher.enabled then IO.unit
    else
      sql"SELECT session_id, access_token, refresh_token FROM sessions"
        .query[(String, String, Option[String])]
        .to[List]
        .transact(xa)
        .flatMap(
          _.traverse_ {
            case (sessionId, accessToken, refreshToken) =>
              if tokenCipher.isEncrypted(accessToken) && refreshToken
                  .forall(tokenCipher.isEncrypted)
              then IO.unit
              else
                for {
                  encryptedAccess <- IO.delay(tokenCipher.encrypt(tokenCipher.decrypt(accessToken)))
                  encryptedRefresh <- IO
                    .delay(refreshToken.map(t => tokenCipher.encrypt(tokenCipher.decrypt(t))))
                  _ <- sql"""
                  UPDATE sessions SET access_token = $encryptedAccess, refresh_token = $encryptedRefresh
                  WHERE session_id = $sessionId
                """.update.run.transact(xa)
                } yield ()
          },
        )

  private def decryptRow(row: SessionRow): IO[SessionRow] =
    IO.delay(
      row.copy(
        accessToken = tokenCipher.decrypt(row.accessToken),
        refreshToken = row.refreshToken.map(tokenCipher.decrypt),
      ),
    )

}

case class SessionRow(
  sessionId: String,
  userId: String,
  userLogin: String,
  displayName: String,
  profileImageUrl: String,
  accessToken: String,
  refreshToken: Option[String],
  tokenExpiresAt: Option[Long],
  createdAt: Long,
  expiresAt: Option[Long],
) {
  def toUser: TwitchUser = TwitchUser(userId, userLogin, displayName, profileImageUrl)
}
