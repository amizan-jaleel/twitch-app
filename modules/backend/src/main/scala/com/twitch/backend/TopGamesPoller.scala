package com.twitch.backend

import cats.effect.*
import com.twitch.core.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.implicits.*
import scala.concurrent.duration.*

class TopGamesPoller(
    clientId: String,
    clientSecret: String,
    client: Client[IO],
    db: Database,
    appToken: Ref[IO, Option[String]],
    settings: AppSettings
) extends TwitchPoller(clientId, clientSecret, client, appToken):

  private def fetchTopGamesPage(token: String, cursor: Option[String]): IO[TwitchSearchCategoriesResponse] =
    val baseUri = uri"https://api.twitch.tv/helix/games/top"
      .withQueryParam("first", "100")
    client.expect[TwitchSearchCategoriesResponse](buildAuthedRequest(baseUri, token, cursor))

  private def fetchAllTopGames(token: String): IO[List[TwitchCategory]] =
    fetchPaginated[TwitchCategory] { (tk, cur) =>
      fetchTopGamesPage(tk, cur).map { resp =>
        new PaginatedResponse[TwitchCategory]:
          def pageData = resp.data
          def pageCursor = resp.pagination.flatMap(_.cursor)
      }
    }(token).map(_.take(settings.topGamesCount))

  private def pollOnce: IO[Unit] =
    for
      games <- withTokenRefresh(fetchAllTopGames)
      unique = games.distinctBy(_.id)
      _ <- db.replaceTopGames(unique)
      _ <- IO.println(s"TopGamesPoller: stored ${unique.size} top games")
    yield ()

  def start: IO[Nothing] =
    IO.println(s"TopGamesPoller: starting (polling every ${settings.topGamesPollInterval.toSeconds}s)") *>
      pollOnce.handleErrorWith(e =>
        IO.println(s"TopGamesPoller first poll failed: $e, retrying in 30s") *>
          IO.sleep(30.seconds) *>
          pollOnce.handleErrorWith(e2 => IO.println(s"TopGamesPoller retry also failed: $e2"))
      ) *>
      (IO.sleep(settings.topGamesPollInterval) *> pollOnce.handleErrorWith(e => IO.println(s"TopGamesPoller error: $e"))).foreverM

object TopGamesPoller:
  def make(
      clientId: String,
      clientSecret: String,
      client: Client[IO],
      db: Database,
      settings: AppSettings
  ): IO[TopGamesPoller] =
    for
      tokenRef <- IO.ref(Option.empty[String])
    yield new TopGamesPoller(clientId, clientSecret, client, db, tokenRef, settings)
