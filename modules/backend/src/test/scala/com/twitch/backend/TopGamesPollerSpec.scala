package com.twitch.backend

import scala.concurrent.duration.*

import cats.effect.*
import doobie.h2.H2Transactor
import munit.CatsEffectSuite

import com.twitch.core.{TwitchCategory, TwitchStream}

class TopGamesPollerSpec extends CatsEffectSuite {

  test("the poller requests its configured limit and stores distinct games from its API") {
    val game1 = TwitchCategory("1", "Game 1", "https://example.com/1")
    val game2 = TwitchCategory("2", "Game 2", "https://example.com/2")
    val oldGame = TwitchCategory("old", "Old game", "https://example.com/old")

    val database = for {
      ec <- Resource.eval(IO.executionContext)
      xa <- H2Transactor.newH2Transactor[IO](
        "jdbc:h2:mem:top_games_poller;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "sa",
        "",
        ec,
      )
      _ <- Resource.eval(db.Schema.initDb(xa, SqlDialect.H2))
    } yield new db.TopGamesRepository(xa)

    database.use { repo =>
      for {
        requestedLimit <- Deferred[IO, Int]
        api = new TwitchPollingApi {
          def liveStreams(categoryIds: List[String]): IO[List[TwitchStream]] =
            IO.raiseError(new AssertionError("Top games poller should not fetch streams"))
          def topGames(limit: Int): IO[List[TwitchCategory]] =
            requestedLimit.complete(limit).as(List(game1, game1, game2))
        }
        settings <- AppSettings.load.map(_.copy(topGamesCount = 3, topGamesPollInterval = 1.hour))
        _ <- repo.replaceTopGames(List(oldGame))
        poller = new TopGamesPoller(api, settings, repo)
        _ <- Resource.make(poller.start.start)(_.cancel).use { _ =>
          def waitForGames: IO[Set[String]] =
            repo.getTopGameIds.flatMap { ids =>
              if ids == Set("1", "2") then IO.pure(ids)
              else IO.sleep(10.millis) *> waitForGames
            }
          for {
            limit <- requestedLimit.get.timeout(5.seconds)
            ids <- waitForGames.timeout(5.seconds)
          } yield {
            assertEquals(limit, 3)
            assertEquals(ids, Set("1", "2"))
          }
        }
      } yield ()
    }
  }

}
