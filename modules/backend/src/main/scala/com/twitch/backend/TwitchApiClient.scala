package com.twitch.backend

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Mutex
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.*

import com.twitch.core.{
  PaginatedResponse,
  TwitchCategory,
  TwitchSearchCategoriesResponse,
  TwitchSearchChannelsResponse,
  TwitchStream,
  TwitchStreamsResponse,
  TwitchTokenResponse,
  TwitchUser,
  TwitchUsersResponse,
}

opaque private type AppAccessToken = String

private object AppAccessToken {
  def apply(s: String): AppAccessToken = s
  extension (t: AppAccessToken) def value: String = t
}

class TwitchApiClient private (
  client: Client[IO],
  clientId: String,
  clientSecret: String,
  streamsPageSize: Int,
  parallelCategories: Int,
  appToken: Ref[IO, Option[AppAccessToken]],
  tokenMutex: Mutex[IO],
) extends TwitchApi
  with TwitchPollingApi {

  def searchCategories(
    query: String,
    after: Option[String],
    accessToken: String,
    pageSize: Int,
  ): IO[TwitchSearchCategoriesResponse] = {
    val uri = uri"https://api.twitch.tv/helix/search/categories"
      .withQueryParam("query", query)
      .withQueryParam("first", pageSize.toString)
      .withOptionQueryParam("after", after)
    client.expect[TwitchSearchCategoriesResponse](authedRequest(uri, accessToken))
  }

  def searchChannels(
    query: String,
    after: Option[String],
    accessToken: String,
    pageSize: Int,
  ): IO[TwitchSearchChannelsResponse] = {
    val uri = uri"https://api.twitch.tv/helix/search/channels"
      .withQueryParam("query", query)
      .withQueryParam("first", pageSize.toString)
      .withOptionQueryParam("after", after)
    client.expect[TwitchSearchChannelsResponse](authedRequest(uri, accessToken))
  }

  def getUser(accessToken: String): IO[TwitchUser] = {
    val req = authedRequest(uri"https://api.twitch.tv/helix/users", accessToken)
    client.expect[TwitchUsersResponse](req).map(_.data.head)
  }

  def exchangeCode(code: String, redirectUri: String): IO[TwitchTokenResponse] = {
    val req =
      Request[IO](method = Method.POST, uri = uri"https://id.twitch.tv/oauth2/token").withEntity(
        UrlForm(
          "client_id" -> clientId,
          "client_secret" -> clientSecret,
          "code" -> code,
          "grant_type" -> "authorization_code",
          "redirect_uri" -> redirectUri,
        ),
      )
    client.run(req).use { resp =>
      if resp.status.isSuccess then resp.as[TwitchTokenResponse]
      else
        resp.bodyText.compile.string.flatMap { errorBody =>
          IO.raiseError(
            new RuntimeException(
              s"unexpected HTTP status: ${resp.status} for request POST https://id.twitch.tv/oauth2/token. Response body: $errorBody",
            ),
          )
        }
    }
  }

  def refreshToken(refreshToken: String): IO[TwitchTokenResponse] = {
    val req =
      Request[IO](method = Method.POST, uri = uri"https://id.twitch.tv/oauth2/token").withEntity(
        UrlForm(
          "client_id" -> clientId,
          "client_secret" -> clientSecret,
          "grant_type" -> "refresh_token",
          "refresh_token" -> refreshToken,
        ),
      )
    client.run(req).use { resp =>
      if resp.status.isSuccess then resp.as[TwitchTokenResponse]
      else IO.raiseError(new RuntimeException(s"Token refresh failed: ${resp.status}"))
    }
  }

  def liveStreams(categoryIds: List[String]): IO[List[TwitchStream]] =
    withTokenRefresh {
      categoryIds
        .parTraverseN(parallelCategories) { categoryId =>
          val baseUri = uri"https://api.twitch.tv/helix/streams"
            .withQueryParam("game_id", categoryId)
            .withQueryParam("first", streamsPageSize.toString)
          fetchPaginated[TwitchStream] { cursor =>
            client.expect[TwitchStreamsResponse](buildAuthedRequest(baseUri, cursor))
          }
        }
        .map(_.flatten)
    }

  def topGames(limit: Int): IO[List[TwitchCategory]] =
    withTokenRefresh {
      val baseUri = uri"https://api.twitch.tv/helix/games/top"
        .withQueryParam("first", "100")
      fetchPaginated[TwitchCategory](
        cursor =>
          client.expect[TwitchSearchCategoriesResponse](buildAuthedRequest(baseUri, cursor)),
        limit = limit,
      )
    }

  private def withTokenRefresh[A](f: AppAccessToken ?=> IO[A]): IO[A] =
    getOrRefreshToken.attempt.flatMap {
      case Right(token) =>
        IO.defer(f(using token)).handleErrorWith { _ =>
          // A different poller may already have replaced the token that failed.
          appToken.update(_.filterNot(_ == token)) *>
            getOrRefreshToken.flatMap(t => f(using t))
        }
      case Left(_) => getOrRefreshToken.flatMap(t => f(using t))
    }

  private def buildAuthedRequest(
    baseUri: Uri,
    cursor: Option[String],
  )(using token: AppAccessToken): Request[IO] = {
    val uriWithCursor = cursor.fold(baseUri)(c => baseUri.withQueryParam("after", c))
    Request[IO](method = Method.GET, uri = uriWithCursor).putHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, token.value)),
      Header.Raw(ci"Client-Id", clientId),
    )
  }

  private def fetchPaginated[A](
    fetchPage: Option[String] => IO[PaginatedResponse[A]],
    limit: Int = Int.MaxValue,
  ): IO[List[A]] = {
    def go(cursor: Option[String], acc: List[A]): IO[List[A]] =
      fetchPage(cursor).flatMap { resp =>
        val newAcc = acc ::: resp.pageData
        if newAcc.size >= limit then IO.pure(newAcc.take(limit))
        else
          resp.pageCursor match {
            case Some(next) if resp.pageData.nonEmpty => go(Some(next), newAcc)
            case _ => IO.pure(newAcc.take(limit))
          }
      }
    go(None, Nil)
  }

  private def fetchAppToken: IO[AppAccessToken] = {
    val req =
      Request[IO](method = Method.POST, uri = uri"https://id.twitch.tv/oauth2/token").withEntity(
        UrlForm(
          "client_id" -> clientId,
          "client_secret" -> clientSecret,
          "grant_type" -> "client_credentials",
        ),
      )
    client.run(req).use { resp =>
      if resp.status.isSuccess then
        resp.as[TwitchTokenResponse].map(r => AppAccessToken(r.access_token))
      else
        resp.bodyText.compile.string.flatMap { body =>
          IO.raiseError(new RuntimeException(s"Failed to get app token: ${resp.status} $body"))
        }
    }
  }

  private def getOrRefreshToken: IO[AppAccessToken] =
    tokenMutex.lock.surround {
      appToken.get.flatMap {
        case Some(t) => IO.pure(t)
        case None => fetchAppToken.flatTap(t => appToken.set(Some(t)))
      }
    }

  private def authedRequest(uri: Uri, accessToken: String): Request[IO] =
    Request[IO](method = Method.GET, uri = uri).putHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, accessToken)),
      Header.Raw(ci"Client-Id", clientId),
    )

}

object TwitchApiClient {

  def make(
    client: Client[IO],
    clientId: String,
    clientSecret: String,
    streamsPageSize: Int,
    parallelCategories: Int,
  ): IO[TwitchApiClient] =
    for {
      tokenRef <- IO.ref(Option.empty[AppAccessToken])
      tokenMutex <- Mutex[IO]
    } yield new TwitchApiClient(
      client = client,
      clientId = clientId,
      clientSecret = clientSecret,
      streamsPageSize = streamsPageSize,
      parallelCategories = parallelCategories,
      appToken = tokenRef,
      tokenMutex = tokenMutex,
    )

}
