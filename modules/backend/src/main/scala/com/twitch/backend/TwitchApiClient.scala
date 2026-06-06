package com.twitch.backend

import cats.effect.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.*

import com.twitch.core.{
  PaginatedResponse,
  TwitchSearchCategoriesResponse,
  TwitchSearchChannelsResponse,
  TwitchTokenResponse,
  TwitchUser,
  TwitchUsersResponse,
}

opaque type AppAccessToken = String
object AppAccessToken {
  def apply(s: String): AppAccessToken = s
  extension (t: AppAccessToken) def value: String = t
}

class TwitchApiClient(
  val client: Client[IO],
  clientId: String,
  clientSecret: String,
  private val appToken: Ref[IO, Option[AppAccessToken]],
) extends TwitchApi {

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

  def withTokenRefresh[A](f: AppAccessToken ?=> IO[A]): IO[A] =
    getOrRefreshToken.flatMap(t => f(using t)).handleErrorWith { _ =>
      appToken.set(None) *> getOrRefreshToken.flatMap(t => f(using t))
    }

  def buildAuthedRequest(
    baseUri: Uri,
    cursor: Option[String],
  )(using token: AppAccessToken): Request[IO] = {
    val uriWithCursor = cursor.fold(baseUri)(c => baseUri.withQueryParam("after", c))
    Request[IO](method = Method.GET, uri = uriWithCursor).putHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, token.value)),
      Header.Raw(ci"Client-Id", clientId),
    )
  }

  def fetchPaginated[A](
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
    appToken.get.flatMap {
      case Some(t) => IO.pure(t)
      case None => fetchAppToken.flatTap(t => appToken.set(Some(t)))
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
  ): IO[TwitchApiClient] =
    IO.ref(Option.empty[AppAccessToken]).map { tokenRef =>
      new TwitchApiClient(
        client = client,
        clientId = clientId,
        clientSecret = clientSecret,
        appToken = tokenRef,
      )
    }

}
