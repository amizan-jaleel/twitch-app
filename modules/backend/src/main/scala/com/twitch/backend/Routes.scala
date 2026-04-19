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
import org.http4s.SameSite
import org.typelevel.ci.*
import java.util.UUID
import java.time.Instant
import cats.effect.std.Queue
import io.circe.syntax.*
import com.twitch.core.*

case class SessionData(
    user: TwitchUser,
    accessToken: String,
    refreshToken: Option[String],
    tokenExpiresAt: Option[Long],
    sessionId: String
)

class Routes(
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    client: Client[IO],
    pendingOAuthStates: Ref[IO, Set[String]],
    db: Database,
    notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]],
    settings: AppSettings,
    emailService: Option[EmailService]
):

  private val secureCookies = redirectUri.startsWith("https")

  private def sendWelcomeEmailIfNeeded(user: TwitchUser): IO[Unit] =
    (user.email, emailService) match
      case (Some(email), Some(es)) =>
        es.sendWelcomeEmail(email, user.display_name)
          .flatMap(_ => db.markWelcomeEmailSent(user.id))
          .handleErrorWith(err =>
            IO.println(s"Failed to send welcome email to ${user.id}: ${err.getMessage}")
          )
          .start
          .void
      case _ =>
        IO.println(s"Skipping welcome email for ${user.id} (no email or email service not configured)")

  private def getSession(req: Request[IO]): IO[Option[SessionData]] =
    req.cookies.find(_.name == "session_id").map(_.content) match
      case None => IO.pure(None)
      case Some(sid) =>
        db.getSession(sid).map(_.map(row =>
          SessionData(row.toUser, row.accessToken, row.refreshToken, row.tokenExpiresAt, row.sessionId)
        ))

  private def refreshTokenIfNeeded(data: SessionData): IO[SessionData] =
    val needsRefresh = data.tokenExpiresAt.exists { expiresAt =>
      Instant.now().getEpochSecond >= expiresAt - 300 // refresh 5 min before expiry
    }
    if !needsRefresh || data.refreshToken.isEmpty then IO.pure(data)
    else
      val req = Request[IO](method = Method.POST, uri = uri"https://id.twitch.tv/oauth2/token").withEntity(
        UrlForm(
          "client_id" -> clientId,
          "client_secret" -> clientSecret,
          "grant_type" -> "refresh_token",
          "refresh_token" -> data.refreshToken.get
        )
      )
      client.run(req).use { resp =>
        if resp.status.isSuccess then
          resp.as[TwitchTokenResponse].flatMap { tokenResp =>
            val expiresAt = Some(Instant.now().plusSeconds(tokenResp.expires_in.toLong))
            db.updateSessionToken(data.sessionId, tokenResp.access_token, tokenResp.refresh_token.orElse(data.refreshToken), expiresAt) *>
              IO.pure(data.copy(
                accessToken = tokenResp.access_token,
                refreshToken = tokenResp.refresh_token.orElse(data.refreshToken),
                tokenExpiresAt = expiresAt.map(_.getEpochSecond)
              ))
          }
        else IO.pure(data) // if refresh fails, try with existing token
      }

  private object CodeQueryParamMatcher extends QueryParamDecoderMatcher[String]("code")
  private object StateQueryParamMatcher extends QueryParamDecoderMatcher[String]("state")
  private object SearchQueryParamMatcher extends QueryParamDecoderMatcher[String]("query")
  private object AfterQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("after")

  def authRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "auth" / "login" =>
      val state = UUID.randomUUID().toString
      val authorizeUri = s"https://id.twitch.tv/oauth2/authorize?client_id=$clientId&redirect_uri=$redirectUri&response_type=code&scope=user:read:email&state=$state"
      pendingOAuthStates.update(_ + state) *>
        Found(Location(Uri.unsafeFromString(authorizeUri)))

    case GET -> Root / "auth" / "callback" :? CodeQueryParamMatcher(code) +& StateQueryParamMatcher(state) =>
      val flow = for {
        pending <- pendingOAuthStates.get
        _ <- IO.raiseUnless(pending.contains(state))(new RuntimeException("Invalid OAuth state parameter"))
        _ <- pendingOAuthStates.update(_ - state)
        _ <- IO.println("Received auth callback")
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
        
        _ <- IO.println("Token exchange successful")
        userReq = Request[IO](method = Method.GET, uri = uri"https://api.twitch.tv/helix/users").putHeaders(
          Authorization(Credentials.Token(AuthScheme.Bearer, tokenResponse.access_token)),
          Header.Raw(ci"Client-Id", clientId)
        )
        userResponse <- client.expect[TwitchUsersResponse](userReq)
        user = userResponse.data.head
        _ <- IO.println(s"Found user: ${user.display_name}")
        existingUser <- db.findUser(user.id)
        _ <- existingUser match
          case None =>
            db.insertUser(user.id, user.login, user.display_name, user.email) *>
              sendWelcomeEmailIfNeeded(user)
          case Some(existing) =>
            db.updateLastLogin(user.id, user.login, user.display_name, user.email) *>
              (if !existing.welcomeEmailSent then sendWelcomeEmailIfNeeded(user) else IO.unit)
        sessionId = UUID.randomUUID().toString
        tokenExpiresAt = Some(Instant.now().plusSeconds(tokenResponse.expires_in.toLong))
        _ <- db.createSession(sessionId, user, tokenResponse.access_token, tokenResponse.refresh_token, tokenExpiresAt)
        res <- Found(Location(uri"/")).map(_.addCookie(ResponseCookie(
          "session_id", sessionId,
          path = Some("/"),
          httpOnly = true,
          secure = secureCookies,
          sameSite = Some(SameSite.Lax)
        )))
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
    case req @ GET -> Root / "search" / "categories" :? SearchQueryParamMatcher(query) +& AfterQueryParamMatcher(after) =>
      getSession(req).flatMap {
        case Some(data) =>
          refreshTokenIfNeeded(data).flatMap { refreshed =>
            val uri = uri"https://api.twitch.tv/helix/search/categories"
              .withQueryParam("query", query)
              .withQueryParam("first", settings.searchPageSize.toString)
              .withOptionQueryParam("after", after)
            val searchReq = Request[IO](method = Method.GET, uri = uri).putHeaders(
              Authorization(Credentials.Token(AuthScheme.Bearer, refreshed.accessToken)),
              Header.Raw(ci"Client-Id", clientId)
            )
            client.expect[TwitchSearchCategoriesResponse](searchReq).flatMap(Ok(_))
          }
        case None => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "search" / "channels" :? SearchQueryParamMatcher(query) +& AfterQueryParamMatcher(after) =>
      getSession(req).flatMap {
        case Some(data) =>
          refreshTokenIfNeeded(data).flatMap { refreshed =>
            val uri = uri"https://api.twitch.tv/helix/search/channels"
              .withQueryParam("query", query)
              .withQueryParam("first", settings.searchPageSize.toString)
              .withOptionQueryParam("after", after)
            val searchReq = Request[IO](method = Method.GET, uri = uri).putHeaders(
              Authorization(Credentials.Token(AuthScheme.Bearer, refreshed.accessToken)),
              Header.Raw(ci"Client-Id", clientId)
            )
            client.expect[TwitchSearchChannelsResponse](searchReq).flatMap(Ok(_))
          }
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "logout" =>
      val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
      for {
        _ <- sessionId.fold(IO.unit)(id => db.deleteSession(id))
        res <- Ok("Logged out").map(_.removeCookie("session_id"))
      } yield res
    case req @ GET -> Root / "tag-filters" =>
      getSession(req).flatMap {
        case Some(data) =>
          db.getTagFilters(data.user.id).flatMap(filters => Ok(TagFiltersResponse(filters)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "tag-filters" / "add" =>
      req.as[AddTagFilterRequest].flatMap { body =>
        getSession(req).flatMap {
          case Some(data) =>
            val tag = body.tag.trim
            if tag.isEmpty || tag.length > 25 then BadRequest("Tag must be 1-25 characters")
            else if body.filterType != "include" && body.filterType != "exclude" then BadRequest("filterType must be 'include' or 'exclude'")
            else db.addTagFilter(data.user.id, body.filterType, tag) *> Ok("Filter added")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "tag-filters" / "remove" =>
      req.as[AddTagFilterRequest].flatMap { body =>
        getSession(req).flatMap {
          case Some(data) =>
            db.removeTagFilter(data.user.id, body.filterType, body.tag) *> Ok("Filter removed")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ GET -> Root / "ignored-streamers" =>
      getSession(req).flatMap {
        case Some(data) =>
          db.getIgnoredStreamers(data.user.id).flatMap(streamers => Ok(IgnoredStreamersResponse(streamers)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "ignored-streamers" / "add" =>
      req.as[AddIgnoredStreamerRequest].flatMap { body =>
        getSession(req).flatMap {
          case Some(data) =>
            if body.streamerId.trim.isEmpty then BadRequest("streamerId is required")
            else db.addIgnoredStreamer(data.user.id, body.streamerId, body.streamerLogin, body.streamerName) *> Ok("Streamer ignored")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "ignored-streamers" / "remove" =>
      req.as[RemoveIgnoredStreamerRequest].flatMap { body =>
        getSession(req).flatMap {
          case Some(data) =>
            db.removeIgnoredStreamer(data.user.id, body.streamerId) *> Ok("Streamer unignored")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "push" / "register" =>
      req.as[PushRegisterRequest].flatMap { body =>
        getSession(req).flatMap {
          case Some(data) =>
            if body.platform != "ios" && body.platform != "android" && body.platform != "web" then
              BadRequest("platform must be 'ios', 'android', or 'web'")
            else
              db.savePushSubscription(data.user.id, body.token, body.platform) *> Ok("Registered")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "push" / "unregister" =>
      req.as[PushUnregisterRequest].flatMap { body =>
        getSession(req).flatMap {
          case Some(_) =>
            db.deletePushSubscription(body.token) *> Ok("Unregistered")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ GET -> Root / "notifications" / "stream" =>
      getSession(req).flatMap {
        case None => Forbidden("Not logged in")
        case Some(data) =>
          val sessionId = req.cookies.find(_.name == "session_id").map(_.content).getOrElse("unknown")
          Queue.unbounded[IO, StreamNotification].flatMap { queue =>
            notificationQueues.update(_ + (sessionId -> (data.user.id, queue))) *> {
              val eventStream: fs2.Stream[IO, ServerSentEvent] =
                fs2.Stream.fromQueueUnterminated(queue)
                  .map { n =>
                    ServerSentEvent(data = Some(n.asJson.noSpaces), eventType = Some("stream-live"))
                  }
                  .onFinalize(notificationQueues.update(_ - sessionId))
              Ok(eventStream)
            }
          }
      }
  }
