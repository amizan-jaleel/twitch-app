package com.twitch.backend

import cats.effect.*
import org.http4s.*
import org.http4s.Method.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.headers.{Authorization, Location}
import org.typelevel.ci.*
import java.util.UUID
import com.twitch.core.*

case class SessionData(
    user: TwitchUser,
    accessToken: String
)

class Routes(
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    client: Client[IO],
    userSession: Ref[IO, Map[String, SessionData]],
    db: Database
):

  private def getSession(req: Request[IO]): IO[Option[SessionData]] = {
    val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
    sessionId.fold(IO.pure(None: Option[SessionData]))(id => userSession.get.map(_.get(id)))
  }

  private object CodeQueryParamMatcher extends QueryParamDecoderMatcher[String]("code")
  private object SearchQueryParamMatcher extends QueryParamDecoderMatcher[String]("query")

  def authRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "auth" / "callback" :? CodeQueryParamMatcher(code) =>
      val flow = for {
        _ <- IO.println(s"Received auth code: $code")
        req = Request[IO](method = Method.POST, uri = uri"https://id.twitch.tv/oauth2/token").withEntity(
          UrlForm(
            "client_id" -> clientId,
            "client_secret" -> clientSecret,
            "code" -> code,
            "grant_type" -> "authorization_code",
            "redirect_uri" -> redirectUri
          )
        )
        
        tokenResponse <- client.run(req).use { resp =>
          if (resp.status.isSuccess) {
            resp.as[TwitchTokenResponse]
          } else {
            resp.bodyText.compile.string.flatMap { errorBody =>
              IO.raiseError(new RuntimeException(s"unexpected HTTP status: ${resp.status} for request POST https://id.twitch.tv/oauth2/token. Response body: $errorBody"))
            }
          }
        }
        
        _ <- IO.println(s"Received token: ${tokenResponse.access_token}")
        userReq = Request[IO](method = Method.GET, uri = uri"https://api.twitch.tv/helix/users").putHeaders(
          Authorization(Credentials.Token(AuthScheme.Bearer, tokenResponse.access_token)),
          Header.Raw(ci"Client-Id", clientId)
        )
        userResponse <- client.expect[TwitchUsersResponse](userReq)
        user = userResponse.data.head
        _ <- IO.println(s"Found user: ${user.display_name}")
        sessionId = UUID.randomUUID().toString
        _ <- userSession.update(_ + (sessionId -> SessionData(user, tokenResponse.access_token)))
        res <- Found(Location(uri"/")).map(_.addCookie(ResponseCookie("session_id", sessionId, path = Some("/"), httpOnly = true)))
      } yield res

      flow.handleErrorWith { err =>
        IO.println(s"Auth flow failed: ${err.getMessage}") *>
          InternalServerError(s"Auth flow failed. Check server logs. Error: ${err.getMessage}")
      }
  }

  def apiRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "config" =>
      Ok(AppConfig(clientId))
    case req @ GET -> Root / "user" =>
      getSession(req).flatMap {
        case Some(data) => Ok(data.user)
        case None       => NotFound("Not logged in")
      }
    case req @ GET -> Root / "followed" =>
      getSession(req).flatMap {
        case Some(data) =>
          db.getFollowed(data.user.id).flatMap(cats => Ok(FollowedCategoriesResponse(cats)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "follow" =>
      req.as[FollowRequest].flatMap { followReq =>
        getSession(req).flatMap {
          case Some(data) =>
            db.follow(data.user.id, followReq.category) *> Ok("Followed")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "unfollow" / categoryId =>
      getSession(req).flatMap {
        case Some(data) =>
          db.unfollow(data.user.id, categoryId) *> Ok("Unfollowed")
        case None => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "search" / "categories" :? SearchQueryParamMatcher(query) =>
      getSession(req).flatMap {
        case Some(data) =>
          val uri = uri"https://api.twitch.tv/helix/search/categories".withQueryParam("query", query)
          val searchReq = Request[IO](method = Method.GET, uri = uri).putHeaders(
            Authorization(Credentials.Token(AuthScheme.Bearer, data.accessToken)),
            Header.Raw(ci"Client-Id", clientId)
          )
          client.expect[TwitchSearchCategoriesResponse](searchReq).flatMap(Ok(_))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "logout" =>
      val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
      for {
        _ <- sessionId.fold(IO.unit)(id => userSession.update(_ - id))
        res <- Ok("Logged out").map(_.removeCookie("session_id"))
      } yield res
  }
