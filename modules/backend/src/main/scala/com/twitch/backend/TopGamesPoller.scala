package com.twitch.backend

import scala.concurrent.duration.*

import cats.effect.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.implicits.*

import com.twitch.backend.db.TopGamesRepository
import com.twitch.core.{TwitchCategory, TwitchSearchCategoriesResponse}

class TopGamesPoller(
  twitchClient: TwitchApiClient,
  settings: AppSettings,
  topGamesRepo: TopGamesRepository,
) {

  private def fetchTopGamesPage(
    cursor: Option[String],
  )(using AppAccessToken): IO[TwitchSearchCategoriesResponse] = {
    val baseUri = uri"https://api.twitch.tv/helix/games/top"
      .withQueryParam("first", "100")
    twitchClient.client.expect[TwitchSearchCategoriesResponse](
      twitchClient.buildAuthedRequest(baseUri, cursor),
    )
  }

  private def fetchAllTopGames(using AppAccessToken): IO[List[TwitchCategory]] =
    twitchClient.fetchPaginated[TwitchCategory](fetchTopGamesPage, limit = settings.topGamesCount)

  private def pollOnce: IO[Unit] =
    for {
      games <- twitchClient.withTokenRefresh(fetchAllTopGames)
      unique = games.distinctBy(_.id)
      _ <- topGamesRepo.replaceTopGames(unique)
      _ <- IO.println(s"TopGamesPoller: stored ${unique.size} top games")
    } yield ()

  def start: IO[Nothing] =
    IO.println(
      s"TopGamesPoller: starting (polling every ${settings.topGamesPollInterval.toSeconds}s)",
    ) *>
      pollOnce.handleErrorWith(e =>
        IO.println(s"TopGamesPoller first poll failed: $e, retrying in 30s") *>
          IO.sleep(30.seconds) *>
          pollOnce.handleErrorWith(e2 => IO.println(s"TopGamesPoller retry also failed: $e2")),
      ) *>
      (IO.sleep(settings.topGamesPollInterval) *> pollOnce.handleErrorWith(e =>
        IO.println(s"TopGamesPoller error: $e"),
      )).foreverM

}

object TopGamesPoller {

  def make(
    twitchClient: TwitchApiClient,
    settings: AppSettings,
    topGamesRepo: TopGamesRepository,
  ): IO[TopGamesPoller] =
    IO.pure(
      new TopGamesPoller(
        twitchClient = twitchClient,
        settings = settings,
        topGamesRepo = topGamesRepo,
      ),
    )

}
