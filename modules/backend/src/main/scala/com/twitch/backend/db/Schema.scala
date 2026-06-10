package com.twitch.backend.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import com.twitch.backend.SqlDialect

object Schema {

  def initDb(xa: Transactor[IO], dialect: SqlDialect): IO[Unit] = {
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
        created_at BIGINT NOT NULL,
        expires_at BIGINT
      )
    """.update.run
    val migrateSessionsAddExpiresAt = sql"""
      ALTER TABLE sessions ADD COLUMN IF NOT EXISTS expires_at BIGINT
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
    val createPushUniqueIndex =
      sql"CREATE UNIQUE INDEX IF NOT EXISTS idx_push_user_token ON push_subscriptions (user_id, device_token)"
        .update
        .run
    val createIgnoredStreamers = sql"""
      CREATE TABLE IF NOT EXISTS ignored_streamers (
        user_id VARCHAR NOT NULL,
        streamer_id VARCHAR NOT NULL,
        streamer_login VARCHAR NOT NULL,
        streamer_name VARCHAR NOT NULL,
        PRIMARY KEY (user_id, streamer_id)
      )
    """.update.run
    val createTopGames = sql"""
      CREATE TABLE IF NOT EXISTS top_games (
        category_id VARCHAR PRIMARY KEY,
        name VARCHAR NOT NULL,
        box_art_url VARCHAR NOT NULL
      )
    """.update.run
    val createFollowedCategoryIndex =
      sql"CREATE INDEX IF NOT EXISTS idx_followed_category_user ON followed_categories (category_id, user_id)"
        .update
        .run
    (createFollowed *>
      createTagFilters *>
      createIgnoredStreamers *>
      createSessions *>
      migrateSessionsAddExpiresAt *>
      createUsers *>
      migrateUsersAddLogin *>
      migrateUsersAddDisplayName *>
      createPushSubscriptions *>
      createPushUniqueIndex *>
      createTopGames *>
      createFollowedCategoryIndex)
      .transact(xa)
      .void
  }

}
