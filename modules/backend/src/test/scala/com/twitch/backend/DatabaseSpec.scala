package com.twitch.backend

import java.time.Instant
import scala.concurrent.duration.*

import cats.effect.*
import doobie.Transactor
import doobie.h2.H2Transactor
import munit.CatsEffectSuite

import com.twitch.backend.auth.SessionTokenCipher
import com.twitch.core.*

class DatabaseSpec extends CatsEffectSuite {

  case class Repos(
    xa: Transactor[IO],
    followRepo: db.FollowRepository,
    tagFilterRepo: db.TagFilterRepository,
    userRepo: db.UserRepository,
    topGamesRepo: db.TopGamesRepository,
    pushRepo: db.PushSubscriptionRepository,
  )

  private val dbFixture = ResourceSuiteLocalFixture(
    "database",
    for {
      ec <- Resource.eval(IO.executionContext)
      xa <- H2Transactor.newH2Transactor[IO](
        "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "sa",
        "",
        ec,
      )
      _ <- Resource.eval(db.Schema.initDb(xa, SqlDialect.H2))
    } yield Repos(
      xa,
      new db.FollowRepository(xa, SqlDialect.H2),
      new db.TagFilterRepository(xa, SqlDialect.H2),
      new db.UserRepository(xa),
      new db.TopGamesRepository(xa),
      new db.PushSubscriptionRepository(xa, SqlDialect.H2),
    ),
  )

  override def munitFixtures = List(dbFixture)

  private def repos = dbFixture()

  // ── Tag filter tests ───────────────────────────────────────────────

  test("addTagFilter normalizes tag to lowercase") {
    for {
      _ <- repos.tagFilterRepo.addTagFilter("user1", "include", "ENGLISH")
      filters <- repos.tagFilterRepo.getTagFilters("user1")
    } yield assertEquals(filters.map(_.tag), List("english"))
  }

  test("addTagFilter and getTagFilters round-trip") {
    for {
      _ <- repos.tagFilterRepo.addTagFilter("user2", "include", "english")
      _ <- repos.tagFilterRepo.addTagFilter("user2", "exclude", "speedrun")
      filters <- repos.tagFilterRepo.getTagFilters("user2")
    } yield {
      assertEquals(filters.size, 2)
      assert(filters.exists(f => f.filterType == "include" && f.tag == "english"))
      assert(filters.exists(f => f.filterType == "exclude" && f.tag == "speedrun"))
    }
  }

  test("addTagFilter is idempotent (no duplicate)") {
    for {
      _ <- repos.tagFilterRepo.addTagFilter("user3", "include", "english")
      _ <- repos.tagFilterRepo.addTagFilter("user3", "include", "english")
      filters <- repos.tagFilterRepo.getTagFilters("user3")
    } yield assertEquals(filters.count(_.tag == "english"), 1)
  }

  test("removeTagFilter removes existing filter") {
    for {
      _ <- repos.tagFilterRepo.addTagFilter("user4", "include", "english")
      _ <- repos.tagFilterRepo.removeTagFilter("user4", "include", "english")
      filters <- repos.tagFilterRepo.getTagFilters("user4")
    } yield assertEquals(filters.size, 0)
  }

  test("removeTagFilter on non-existent filter does not error") {
    repos.tagFilterRepo.removeTagFilter("user5", "include", "nonexistent")
  }

  // ── Follow tests ───────────────────────────────────────────────────

  private val testCategory = TwitchCategory("cat1", "Test Category", "https://example.com/art.jpg")

  test("follow and getFollowed round-trip") {
    for {
      _ <- repos.followRepo.follow("user6", testCategory)
      followed <- repos.followRepo.getFollowed("user6")
    } yield {
      assertEquals(followed.size, 1)
      assertEquals(followed.head.id, "cat1")
      assertEquals(followed.head.name, "Test Category")
    }
  }

  test("unfollow removes category") {
    for {
      _ <- repos.followRepo.follow("user7", testCategory)
      _ <- repos.followRepo.unfollow("user7", "cat1")
      followed <- repos.followRepo.getFollowed("user7")
    } yield assertEquals(followed.size, 0)
  }

  test("getAllFollowedCategories deduplicates across users") {
    for {
      _ <- repos.followRepo.follow("user8", testCategory)
      _ <- repos.followRepo.follow("user9", testCategory)
      all <- repos.followRepo.getAllFollowedCategories
    } yield {
      val matching = all.filter(_.id == "cat1")
      assertEquals(matching.size, 1)
    }
  }

  // ── User tests ─────────────────────────────────────────────────────

  test("insertUser and findUser round-trip") {
    for {
      _ <- repos
        .userRepo
        .insertUser("newuser1", "testlogin1", "TestUser1", Some("test@example.com"))
      found <- repos.userRepo.findUser("newuser1")
    } yield {
      assert(found.isDefined)
      assertEquals(found.get.userId, "newuser1")
      assertEquals(found.get.login, Some("testlogin1"))
      assertEquals(found.get.displayName, Some("TestUser1"))
      assertEquals(found.get.email, Some("test@example.com"))
      assertEquals(found.get.welcomeEmailSent, false)
    }
  }

  test("findUser returns None for unknown user") {
    for found <- repos.userRepo.findUser("nonexistent")
    yield assert(found.isEmpty)
  }

  test("markWelcomeEmailSent updates flag") {
    for {
      _ <- repos
        .userRepo
        .insertUser("newuser2", "testlogin2", "TestUser2", Some("test2@example.com"))
      _ <- repos.userRepo.markWelcomeEmailSent("newuser2")
      found <- repos.userRepo.findUser("newuser2")
    } yield assertEquals(found.get.welcomeEmailSent, true)
  }

  test("updateLastLogin updates timestamp") {
    for {
      _ <- repos
        .userRepo
        .insertUser("newuser3", "testlogin3", "TestUser3", Some("test3@example.com"))
      before <- repos.userRepo.findUser("newuser3")
      _ <- IO.sleep(scala.concurrent.duration.Duration(10, "ms"))
      _ <- repos
        .userRepo
        .updateLastLogin("newuser3", "testlogin3", "TestUser3", Some("test3@example.com"))
      after <- repos.userRepo.findUser("newuser3")
    } yield assert(after.get.lastLoginAt >= before.get.lastLoginAt)
  }

  test("updateLastLogin updates email via COALESCE") {
    for {
      _ <- repos.userRepo.insertUser("newuser4", "testlogin4", "TestUser4", None)
      _ <- repos
        .userRepo
        .updateLastLogin("newuser4", "testlogin4", "TestUser4", Some("new@example.com"))
      found <- repos.userRepo.findUser("newuser4")
    } yield assertEquals(found.get.email, Some("new@example.com"))
  }

  // ── getUsersFollowingCategories tests ──────────────────────────────

  private val catA = TwitchCategory("catA", "Category A", "https://example.com/a.jpg")
  private val catB = TwitchCategory("catB", "Category B", "https://example.com/b.jpg")

  test("getUsersFollowingCategories returns users following any of the given categories") {
    for {
      _ <- repos.followRepo.follow("fanout1", catA)
      _ <- repos.followRepo.follow("fanout2", catA)
      _ <- repos.followRepo.follow("fanout3", catB)
      result <- repos.followRepo.getUsersFollowingCategories(Set("catA", "catB"))
    } yield assertEquals(result, Set("fanout1", "fanout2", "fanout3"))
  }

  test("getUsersFollowingCategories excludes users not following any given category") {
    for {
      _ <- repos.followRepo.follow("fanout4", catA)
      _ <- repos.followRepo.follow("fanout5", catB)
      result <- repos.followRepo.getUsersFollowingCategories(Set("catA"))
    } yield {
      assert(result.contains("fanout4"))
      assert(!result.contains("fanout5"))
    }
  }

  test("getUsersFollowingCategories returns empty set for empty input") {
    for result <- repos.followRepo.getUsersFollowingCategories(Set.empty)
    yield assert(result.isEmpty)
  }

  test("getUsersFollowingCategories returns empty set for unknown category") {
    for result <- repos.followRepo.getUsersFollowingCategories(Set("nonexistent_cat"))
    yield assert(result.isEmpty)
  }

  test("getUsersFollowingCategories deduplicates users following multiple matched categories") {
    for {
      _ <- repos.followRepo.follow("fanout6", catA)
      _ <- repos.followRepo.follow("fanout6", catB)
      result <- repos.followRepo.getUsersFollowingCategories(Set("catA", "catB"))
    } yield {
      assert(result.contains("fanout6"))
      assertEquals(result.count(_ == "fanout6"), 1)
    }
  }

  // ── Session repository tests ───────────────────────────────────────

  private val sessionUser = TwitchUser(
    id = "session-user",
    login = "sessionlogin",
    display_name = "Session User",
    profile_image_url = "https://example.com/session.png",
  )

  test("getSession returns None and deletes the row when token decryption fails") {
    val oldCipher = SessionTokenCipher.fromSecret("old-session-secret")
    val newCipher = SessionTokenCipher.fromSecret("new-session-secret")
    val oldRepo = new db.SessionRepository(repos.xa, oldCipher)
    val newRepo = new db.SessionRepository(repos.xa, newCipher)
    val sessionId = java.util.UUID.randomUUID().toString
    val now = Instant.now()

    for {
      _ <- oldRepo.createSession(
        sessionId,
        sessionUser,
        "access-token",
        Some("refresh-token"),
        Some(now.plusSeconds(3600)),
        now.plusSeconds(30.days.toSeconds),
        now,
      )
      readWithWrongKey <- newRepo.getSession(sessionId)
      readAfterDelete <- oldRepo.getSession(sessionId)
    } yield {
      assertEquals(readWithWrongKey, None)
      assertEquals(readAfterDelete, None)
    }
  }

  // ── Push subscription tests ────────────────────────────────────────

  test("savePushSubscription stores a token for its owner") {
    for {
      _ <- repos
        .pushRepo
        .savePushSubscription(
          userId = "pushuser1",
          deviceToken = "token-abc",
          platform = "ios",
        )
      rows <- repos.pushRepo.getPushSubscriptionsForUsers(Set("pushuser1"))
    } yield {
      assertEquals(rows.map(_.deviceToken), List("token-abc"))
      assertEquals(rows.map(_.userId), List("pushuser1"))
    }
  }

  test("getPushSubscriptionsForUsers returns no rows for an unknown user") {
    for rows <- repos.pushRepo.getPushSubscriptionsForUsers(Set("unknown-user"))
    yield assertEquals(rows, Nil)
  }

  test("savePushSubscriptionIfUnderLimit rejects a token owned by another user") {
    for {
      first <- repos
        .pushRepo
        .savePushSubscriptionIfUnderLimit(
          userId = "owner-old",
          deviceToken = "shared-token",
          platform = "ios",
          maxPushSubscriptions = 10,
        )
      second <- repos
        .pushRepo
        .savePushSubscriptionIfUnderLimit(
          userId = "owner-new",
          deviceToken = "shared-token",
          platform = "ios",
          maxPushSubscriptions = 10,
        )
      oldRows <- repos.pushRepo.getPushSubscriptionsForUsers(Set("owner-old"))
      newRows <- repos.pushRepo.getPushSubscriptionsForUsers(Set("owner-new"))
    } yield {
      assertEquals(first, db.PushSubscriptionSaveResult.Saved)
      assertEquals(second, db.PushSubscriptionSaveResult.TokenOwnedByAnotherUser)
      assertEquals(oldRows.map(_.deviceToken), List("shared-token"))
      assertEquals(newRows, Nil)
    }
  }

  test("deletePushSubscription only deletes the current user's token") {
    for {
      _ <- repos.pushRepo.savePushSubscription("delete-owner", "delete-token", "ios")
      _ <- repos.pushRepo.deletePushSubscription("other-user", "delete-token")
      stillThere <- repos.pushRepo.getPushSubscriptionsForUsers(Set("delete-owner"))
      _ <- repos.pushRepo.deletePushSubscription("delete-owner", "delete-token")
      deleted <- repos.pushRepo.getPushSubscriptionsForUsers(Set("delete-owner"))
    } yield {
      assertEquals(stillThere.map(_.deviceToken), List("delete-token"))
      assertEquals(deleted, Nil)
    }
  }

}
