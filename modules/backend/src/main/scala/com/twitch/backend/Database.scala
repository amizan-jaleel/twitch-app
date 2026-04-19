package com.twitch.backend

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import cats.data.NonEmptyList
import com.twitch.core.{TwitchCategory, TwitchUser, TagFilter, IgnoredStreamer}
import java.time.Instant

enum SqlDialect:
  case H2, Postgres

class Database(xa: Transactor[IO], dialect: SqlDialect = SqlDialect.H2):

  def initDb: IO[Unit] =
    val createFollowed = sql"""
      CREATE TABLE IF NOT EXISTS followed_categories (
        user_id VARCHAR NOT NULL,
        category_id VARCHAR NOT NULL,
        name VARCHAR NOT NULL,
        box_art_url VARCHAR NOT NULL,
        PRIMARY KEY (user_id, category_id)
      )
    """.update.run
    val createTagFilters = sql"""
      CREATE TABLE IF NOT EXISTS tag_filters (
        user_id VARCHAR NOT NULL,
        filter_type VARCHAR NOT NULL,
        tag VARCHAR(25) NOT NULL,
        PRIMARY KEY (user_id, filter_type, tag)
      )
    """.update.run
    val createSessions = sql"""
      CREATE TABLE IF NOT EXISTS sessions (
        session_id VARCHAR PRIMARY KEY,
        user_id VARCHAR NOT NULL,
        user_login VARCHAR NOT NULL,
        display_name VARCHAR NOT NULL,
        profile_image_url VARCHAR NOT NULL,
        access_token VARCHAR NOT NULL,
        refresh_token VARCHAR,
        token_expires_at BIGINT,
        created_at BIGINT NOT NULL
      )
    """.update.run
    val createUsers = sql"""
      CREATE TABLE IF NOT EXISTS users (
        user_id VARCHAR PRIMARY KEY,
        login VARCHAR,
        display_name VARCHAR,
        email VARCHAR,
        welcome_email_sent BOOLEAN NOT NULL DEFAULT FALSE,
        created_at BIGINT NOT NULL,
        last_login_at BIGINT NOT NULL
      )
    """.update.run
    val migrateUsersAddLogin = sql"""
      ALTER TABLE users ADD COLUMN IF NOT EXISTS login VARCHAR
    """.update.run
    val migrateUsersAddDisplayName = sql"""
      ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR
    """.update.run
    val createPushSubscriptions = sql"""
      CREATE TABLE IF NOT EXISTS push_subscriptions (
        id VARCHAR PRIMARY KEY,
        user_id VARCHAR NOT NULL,
        device_token VARCHAR NOT NULL,
        platform VARCHAR NOT NULL,
        created_at BIGINT NOT NULL
      )
    """.update.run
    val createPushUniqueIndex = dialect match
      case SqlDialect.Postgres =>
        sql"CREATE UNIQUE INDEX IF NOT EXISTS idx_push_user_token ON push_subscriptions (user_id, device_token)".update.run
      case SqlDialect.H2 =>
        sql"CREATE UNIQUE INDEX IF NOT EXISTS idx_push_user_token ON push_subscriptions (user_id, device_token)".update.run
    val createIgnoredStreamers = sql"""
      CREATE TABLE IF NOT EXISTS ignored_streamers (
        user_id VARCHAR NOT NULL,
        streamer_id VARCHAR NOT NULL,
        streamer_login VARCHAR NOT NULL,
        streamer_name VARCHAR NOT NULL,
        PRIMARY KEY (user_id, streamer_id)
      )
    """.update.run
    (createFollowed *> createTagFilters *> createIgnoredStreamers *> createSessions *> createUsers *> migrateUsersAddLogin *> migrateUsersAddDisplayName *> createPushSubscriptions *> createPushUniqueIndex).transact(xa).void

  def getFollowed(userId: String): IO[List[TwitchCategory]] =
    sql"SELECT category_id, name, box_art_url FROM followed_categories WHERE user_id = $userId"
      .query[TwitchCategory]
      .to[List]
      .transact(xa)

  def follow(userId: String, category: TwitchCategory): IO[Unit] =
    val stmt = dialect match
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO followed_categories (user_id, category_id, name, box_art_url)
          VALUES ($userId, ${category.id}, ${category.name}, ${category.box_art_url})
          ON CONFLICT (user_id, category_id) DO UPDATE SET name = EXCLUDED.name, box_art_url = EXCLUDED.box_art_url
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO followed_categories (user_id, category_id, name, box_art_url)
          KEY(user_id, category_id)
          VALUES ($userId, ${category.id}, ${category.name}, ${category.box_art_url})
        """
    stmt.update.run.transact(xa).void

  def unfollow(userId: String, categoryId: String): IO[Unit] =
    sql"DELETE FROM followed_categories WHERE user_id = $userId AND category_id = $categoryId"
      .update.run.transact(xa).void

  def getAllFollowedCategories: IO[List[TwitchCategory]] =
    sql"SELECT DISTINCT category_id, name, box_art_url FROM followed_categories"
      .query[TwitchCategory]
      .to[List]
      .transact(xa)

  def getTagFilters(userId: String): IO[List[TagFilter]] =
    sql"SELECT filter_type, tag FROM tag_filters WHERE user_id = $userId"
      .query[TagFilter]
      .to[List]
      .transact(xa)

  def addTagFilter(userId: String, filterType: String, tag: String): IO[Unit] =
    val normalizedTag = tag.trim.toLowerCase
    val stmt = dialect match
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO tag_filters (user_id, filter_type, tag)
          VALUES ($userId, $filterType, $normalizedTag)
          ON CONFLICT (user_id, filter_type, tag) DO NOTHING
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO tag_filters (user_id, filter_type, tag)
          KEY(user_id, filter_type, tag)
          VALUES ($userId, $filterType, $normalizedTag)
        """
    stmt.update.run.transact(xa).void

  def removeTagFilter(userId: String, filterType: String, tag: String): IO[Unit] =
    val normalizedTag = tag.trim.toLowerCase
    sql"DELETE FROM tag_filters WHERE user_id = $userId AND filter_type = $filterType AND tag = $normalizedTag"
      .update.run.transact(xa).void

  // ── Ignored streamers persistence ───────────────────────────────────

  def getIgnoredStreamers(userId: String): IO[List[IgnoredStreamer]] =
    sql"SELECT streamer_id, streamer_login, streamer_name FROM ignored_streamers WHERE user_id = $userId"
      .query[IgnoredStreamer]
      .to[List]
      .transact(xa)

  def addIgnoredStreamer(userId: String, streamerId: String, streamerLogin: String, streamerName: String): IO[Unit] =
    val stmt = dialect match
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO ignored_streamers (user_id, streamer_id, streamer_login, streamer_name)
          VALUES ($userId, $streamerId, $streamerLogin, $streamerName)
          ON CONFLICT (user_id, streamer_id) DO UPDATE SET streamer_login = EXCLUDED.streamer_login, streamer_name = EXCLUDED.streamer_name
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO ignored_streamers (user_id, streamer_id, streamer_login, streamer_name)
          KEY(user_id, streamer_id)
          VALUES ($userId, $streamerId, $streamerLogin, $streamerName)
        """
    stmt.update.run.transact(xa).void

  def removeIgnoredStreamer(userId: String, streamerId: String): IO[Unit] =
    sql"DELETE FROM ignored_streamers WHERE user_id = $userId AND streamer_id = $streamerId"
      .update.run.transact(xa).void

  // ── User persistence ────────────────────────────────────────────────

  def findUser(userId: String): IO[Option[UserRow]] =
    sql"SELECT user_id, login, display_name, email, welcome_email_sent, created_at, last_login_at FROM users WHERE user_id = $userId"
      .query[UserRow]
      .option
      .transact(xa)

  def insertUser(userId: String, login: String, displayName: String, email: Option[String]): IO[Unit] =
    val now = Instant.now().getEpochSecond
    sql"""
      INSERT INTO users (user_id, login, display_name, email, welcome_email_sent, created_at, last_login_at)
      VALUES ($userId, $login, $displayName, $email, false, $now, $now)
    """.update.run.transact(xa).void

  def updateLastLogin(userId: String, login: String, displayName: String, email: Option[String]): IO[Unit] =
    val now = Instant.now().getEpochSecond
    sql"""
      UPDATE users SET last_login_at = $now, login = $login, display_name = $displayName, email = COALESCE($email, email)
      WHERE user_id = $userId
    """.update.run.transact(xa).void

  def markWelcomeEmailSent(userId: String): IO[Unit] =
    sql"UPDATE users SET welcome_email_sent = true WHERE user_id = $userId"
      .update.run.transact(xa).void

  // ── Push subscription persistence ───────────────────────────────────

  def savePushSubscription(userId: String, deviceToken: String, platform: String): IO[Unit] =
    val id = java.util.UUID.randomUUID().toString
    val now = Instant.now().getEpochSecond
    val stmt = dialect match
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO push_subscriptions (id, user_id, device_token, platform, created_at)
          VALUES ($id, $userId, $deviceToken, $platform, $now)
          ON CONFLICT (user_id, device_token) DO UPDATE SET platform = EXCLUDED.platform
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO push_subscriptions (id, user_id, device_token, platform, created_at)
          KEY(user_id, device_token)
          VALUES ($id, $userId, $deviceToken, $platform, $now)
        """
    stmt.update.run.transact(xa).void

  def deletePushSubscription(deviceToken: String): IO[Unit] =
    sql"DELETE FROM push_subscriptions WHERE device_token = $deviceToken"
      .update.run.transact(xa).void

  def getPushSubscriptionsForUsers(userIds: Set[String]): IO[List[PushSubscriptionRow]] =
    if userIds.isEmpty then IO.pure(Nil)
    else
      val inClause = Fragments.in(fr"user_id", userIds.toList.toNel.get)
      (fr"SELECT id, user_id, device_token, platform, created_at FROM push_subscriptions WHERE" ++ inClause)
        .query[PushSubscriptionRow]
        .to[List]
        .transact(xa)

  // ── Session persistence ─────────────────────────────────────────────

  def createSession(
      sessionId: String,
      user: TwitchUser,
      accessToken: String,
      refreshToken: Option[String],
      tokenExpiresAt: Option[Instant]
  ): IO[Unit] =
    val now = Instant.now().getEpochSecond
    val expiresAt = tokenExpiresAt.map(_.getEpochSecond)
    sql"""
      INSERT INTO sessions (session_id, user_id, user_login, display_name, profile_image_url, access_token, refresh_token, token_expires_at, created_at)
      VALUES ($sessionId, ${user.id}, ${user.login}, ${user.display_name}, ${user.profile_image_url}, $accessToken, $refreshToken, $expiresAt, $now)
    """.update.run.transact(xa).void

  def getSession(sessionId: String): IO[Option[SessionRow]] =
    sql"""
      SELECT session_id, user_id, user_login, display_name, profile_image_url, access_token, refresh_token, token_expires_at, created_at
      FROM sessions WHERE session_id = $sessionId
    """.query[SessionRow].option.transact(xa)

  def updateSessionToken(sessionId: String, accessToken: String, refreshToken: Option[String], tokenExpiresAt: Option[Instant]): IO[Unit] =
    val expiresAt = tokenExpiresAt.map(_.getEpochSecond)
    sql"""
      UPDATE sessions SET access_token = $accessToken, refresh_token = $refreshToken, token_expires_at = $expiresAt
      WHERE session_id = $sessionId
    """.update.run.transact(xa).void

  def deleteSession(sessionId: String): IO[Unit] =
    sql"DELETE FROM sessions WHERE session_id = $sessionId"
      .update.run.transact(xa).void

case class UserRow(
    userId: String,
    login: Option[String],
    displayName: Option[String],
    email: Option[String],
    welcomeEmailSent: Boolean,
    createdAt: Long,
    lastLoginAt: Long
)

case class PushSubscriptionRow(
    id: String,
    userId: String,
    deviceToken: String,
    platform: String,
    createdAt: Long
)

case class SessionRow(
    sessionId: String,
    userId: String,
    userLogin: String,
    displayName: String,
    profileImageUrl: String,
    accessToken: String,
    refreshToken: Option[String],
    tokenExpiresAt: Option[Long],
    createdAt: Long
):
  def toUser: TwitchUser = TwitchUser(userId, userLogin, displayName, profileImageUrl)
