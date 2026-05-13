package com.twitch.backend

import cats.effect.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.implicits.*

import com.twitch.core.{PaginatedResponse, TwitchTokenResponse}

opaque type AppAccessToken = String
object AppAccessToken {
  def apply(s: String): AppAccessToken = s
  extension (t: AppAccessToken) def value: String = t
}

abstract class TwitchPoller(
  protected val clientId: String,
  protected val clientSecret: String,
  protected val client: Client[IO],
  protected val appToken: Ref[IO, Option[AppAccessToken]],
) {

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

  protected def withTokenRefresh[A](f: AppAccessToken ?=> IO[A]): IO[A] =
    getOrRefreshToken.flatMap(t => f(using t)).handleErrorWith { err =>
      val desc = err match {
        case UnexpectedStatus(status, _, _) => s"HTTP $status"
        case _ => err.toString
      }
      IO.println(s"withTokenRefresh: refreshing after $desc") *>
        appToken.set(None) *> getOrRefreshToken.flatMap(t => f(using t))
    }

  protected def buildAuthedRequest(
    baseUri: Uri,
    cursor: Option[String],
  )(using token: AppAccessToken): Request[IO] = {
    import org.http4s.headers.Authorization
    import org.typelevel.ci.*
    val uriWithCursor = cursor.fold(baseUri)(c => baseUri.withQueryParam("after", c))
    Request[IO](method = Method.GET, uri = uriWithCursor).putHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, token.value)),
      Header.Raw(ci"Client-Id", clientId),
    )
  }

  protected def fetchPaginated[A](
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

}
