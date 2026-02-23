package com.example.backend

import cats.effect.*
import doobie.*
import doobie.implicits.*
import com.example.core.TwitchCategory

class Database(xa: Transactor[IO]):

  def initDb: IO[Unit] =
    val sql = sql"""
      CREATE TABLE IF NOT EXISTS followed_categories (
        user_id VARCHAR NOT NULL,
        category_id VARCHAR NOT NULL,
        name VARCHAR NOT NULL,
        box_art_url VARCHAR NOT NULL,
        PRIMARY KEY (user_id, category_id)
      )
    """.update.run
    sql.transact(xa).void

  def getFollowed(userId: String): IO[List[TwitchCategory]] =
    sql"SELECT category_id, name, box_art_url FROM followed_categories WHERE user_id = $userId"
      .query[TwitchCategory]
      .to[List]
      .transact(xa)

  def follow(userId: String, category: TwitchCategory): IO[Unit] =
    sql"""
      MERGE INTO followed_categories (user_id, category_id, name, box_art_url)
      KEY(user_id, category_id)
      VALUES ($userId, ${category.id}, ${category.name}, ${category.box_art_url})
    """.update.run.transact(xa).void

  def unfollow(userId: String, categoryId: String): IO[Unit] =
    sql"DELETE FROM followed_categories WHERE user_id = $userId AND category_id = $categoryId"
      .update.run.transact(xa).void
