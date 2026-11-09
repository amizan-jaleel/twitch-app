package com.twitch.backend

import scala.concurrent.duration.*

import cats.effect.*

import com.twitch.backend.db.TopGamesRepository

class TopGamesPoller(
  twitchClient: TwitchPollingApi,
  settings: AppSettings,
  topGamesRepo: TopGamesRepository,
) {

  private def pollOnce: IO[Unit] =
    for {
      games <- twitchClient.topGames(settings.topGamesCount)
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
