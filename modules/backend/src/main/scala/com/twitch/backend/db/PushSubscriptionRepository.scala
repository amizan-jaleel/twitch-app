package com.twitch.backend.db

import java.time.Instant

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import com.twitch.backend.SqlDialect

class PushSubscriptionRepository(xa: Transactor[IO], dialect: SqlDialect) {

  def savePushSubscription(userId: String, deviceToken: String, platform: String): IO[Unit] = {
    val id = java.util.UUID.randomUUID().toString
    val now = Instant.now().getEpochSecond
    val stmt = dialect match {
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
    }
    // A device represents one current user; reassign the token by removing rows owned by
    // other users before (re)saving, so getUserIdByToken stays unambiguous.
    val deleteStaleOwners =
      sql"DELETE FROM push_subscriptions WHERE device_token = $deviceToken AND user_id <> $userId"
        .update
        .run
    (deleteStaleOwners *> stmt.update.run).transact(xa).void
  }

  def deletePushSubscription(deviceToken: String): IO[Unit] =
    sql"DELETE FROM push_subscriptions WHERE device_token = $deviceToken"
      .update
      .run
      .transact(xa)
      .void

  // A device token maps to a single current user. Order + limit defensively in case a
  // stale row from a previous owner lingers (e.g. account switch without unregister).
  def getUserIdByToken(deviceToken: String): IO[Option[String]] =
    sql"""SELECT user_id FROM push_subscriptions
          WHERE device_token = $deviceToken
          ORDER BY created_at DESC
          LIMIT 1"""
      .query[String]
      .option
      .transact(xa)

  def getPushSubscriptionsForUsers(userIds: Set[String]): IO[List[PushSubscriptionRow]] =
    if userIds.isEmpty then IO.pure(Nil)
    else {
      val inClause = Fragments.in(fr"user_id", userIds.toList.toNel.get)
      (fr"SELECT id, user_id, device_token, platform, created_at FROM push_subscriptions WHERE" ++ inClause)
        .query[PushSubscriptionRow]
        .to[List]
        .transact(xa)
    }

}

case class PushSubscriptionRow(
  id: String,
  userId: String,
  deviceToken: String,
  platform: String,
  createdAt: Long,
)
