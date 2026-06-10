package com.twitch.backend.db

import java.time.Instant

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import com.twitch.backend.SqlDialect

class PushSubscriptionRepository(xa: Transactor[IO], dialect: SqlDialect) {

  def savePushSubscription(userId: String, deviceToken: String, platform: String): IO[Unit] =
    savePushSubscriptionIfUnderLimit(userId, deviceToken, platform, Int.MaxValue).flatMap {
      case PushSubscriptionSaveResult.Saved => IO.unit
      case PushSubscriptionSaveResult.LimitReached =>
        IO.raiseError(new IllegalStateException("Push subscription limit reached"))
      case PushSubscriptionSaveResult.TokenOwnedByAnotherUser =>
        IO.raiseError(new IllegalStateException("Push token already belongs to another user"))
    }

  def savePushSubscriptionIfUnderLimit(
    userId: String,
    deviceToken: String,
    platform: String,
    maxPushSubscriptions: Int,
  ): IO[PushSubscriptionSaveResult] = {
    val id = java.util.UUID.randomUUID().toString
    val now = Instant.now().getEpochSecond
    val stmt = dialect match {
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO push_subscriptions (id, user_id, device_token, platform, created_at)
          SELECT $id, $userId, $deviceToken, $platform, $now
          WHERE NOT EXISTS (
            SELECT 1 FROM push_subscriptions WHERE device_token = $deviceToken AND user_id <> $userId
          ) AND (
            EXISTS (
              SELECT 1 FROM push_subscriptions WHERE user_id = $userId AND device_token = $deviceToken
            ) OR (
              SELECT COUNT(*) FROM push_subscriptions WHERE user_id = $userId
            ) < $maxPushSubscriptions
          )
          ON CONFLICT (user_id, device_token) DO UPDATE SET platform = EXCLUDED.platform
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO push_subscriptions (id, user_id, device_token, platform, created_at)
          KEY(user_id, device_token)
          SELECT $id, $userId, $deviceToken, $platform, $now
          WHERE NOT EXISTS (
            SELECT 1 FROM push_subscriptions WHERE device_token = $deviceToken AND user_id <> $userId
          ) AND (
            EXISTS (
              SELECT 1 FROM push_subscriptions WHERE user_id = $userId AND device_token = $deviceToken
            ) OR (
              SELECT COUNT(*) FROM push_subscriptions WHERE user_id = $userId
            ) < $maxPushSubscriptions
          )
        """
    }
    stmt
      .update
      .run
      .transact(xa)
      .flatMap {
        case changed if changed > 0 => IO.pure(PushSubscriptionSaveResult.Saved)
        case _ =>
          isDeviceTokenOwnedByAnotherUser(userId, deviceToken).map {
            case true => PushSubscriptionSaveResult.TokenOwnedByAnotherUser
            case false => PushSubscriptionSaveResult.LimitReached
          }
      }
  }

  def deletePushSubscription(userId: String, deviceToken: String): IO[Unit] =
    sql"DELETE FROM push_subscriptions WHERE user_id = $userId AND device_token = $deviceToken"
      .update
      .run
      .transact(xa)
      .void

  private def isDeviceTokenOwnedByAnotherUser(userId: String, deviceToken: String): IO[Boolean] =
    sql"""SELECT 1 FROM push_subscriptions
          WHERE device_token = $deviceToken AND user_id <> $userId
          LIMIT 1"""
      .query[Int]
      .option
      .map(_.isDefined)
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

enum PushSubscriptionSaveResult {
  case Saved, LimitReached, TokenOwnedByAnotherUser
}
