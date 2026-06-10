package com.twitch.backend.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import com.twitch.core.TwitchCategory
import com.twitch.backend.SqlDialect

class FollowRepository(xa: Transactor[IO], dialect: SqlDialect) {

  def getFollowed(userId: String): IO[List[TwitchCategory]] =
    sql"SELECT category_id, name, box_art_url FROM followed_categories WHERE user_id = $userId"
      .query[TwitchCategory]
      .to[List]
      .transact(xa)

  def followIfUnderLimit(
    userId: String,
    category: TwitchCategory,
    maxFollowed: Int,
  ): IO[Boolean] = {
    val stmt = dialect match {
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO followed_categories (user_id, category_id, name, box_art_url)
          SELECT $userId, ${category.id}, ${category.name}, ${category.box_art_url}
          WHERE EXISTS (
            SELECT 1 FROM followed_categories WHERE user_id = $userId AND category_id = ${category.id}
          ) OR (
            SELECT COUNT(*) FROM followed_categories WHERE user_id = $userId
          ) < $maxFollowed
          ON CONFLICT (user_id, category_id) DO UPDATE SET name = EXCLUDED.name, box_art_url = EXCLUDED.box_art_url
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO followed_categories (user_id, category_id, name, box_art_url)
          KEY(user_id, category_id)
          SELECT $userId, ${category.id}, ${category.name}, ${category.box_art_url}
          WHERE EXISTS (
            SELECT 1 FROM followed_categories WHERE user_id = $userId AND category_id = ${category.id}
          ) OR (
            SELECT COUNT(*) FROM followed_categories WHERE user_id = $userId
          ) < $maxFollowed
        """
    }
    stmt.update.run.map(_ > 0).transact(xa)
  }

  def follow(userId: String, category: TwitchCategory): IO[Unit] = {
    val stmt = dialect match {
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
    }
    stmt.update.run.transact(xa).void
  }

  def unfollow(userId: String, categoryId: String): IO[Unit] =
    sql"DELETE FROM followed_categories WHERE user_id = $userId AND category_id = $categoryId"
      .update
      .run
      .transact(xa)
      .void

  def getAllFollowedCategories: IO[List[TwitchCategory]] =
    sql"SELECT DISTINCT category_id, name, box_art_url FROM followed_categories"
      .query[TwitchCategory]
      .to[List]
      .transact(xa)

  def getUsersFollowingCategories(categoryIds: Set[String]): IO[Set[String]] =
    if categoryIds.isEmpty then IO.pure(Set.empty)
    else {
      val inClause = Fragments.in(fr"category_id", categoryIds.toList.toNel.get)
      (fr"SELECT DISTINCT user_id FROM followed_categories WHERE" ++ inClause)
        .query[String]
        .to[Set]
        .transact(xa)
    }

}
