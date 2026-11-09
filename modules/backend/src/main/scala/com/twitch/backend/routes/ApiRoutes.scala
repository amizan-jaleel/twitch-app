package com.twitch.backend.routes

import cats.effect.*
import cats.effect.std.Queue
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*

import com.twitch.backend.{AppSettings, TwitchApi, Validation}
import com.twitch.backend.auth.{SessionData, SessionManager}
import com.twitch.backend.db.{
  FollowRepository,
  IgnoredStreamerRepository,
  PushSubscriptionRepository,
  SessionRepository,
  TagFilterRepository,
  TopGamesRepository,
}
import com.twitch.core.{
  AddIgnoredStreamerRequest,
  AddTagFilterRequest,
  AppConfig,
  FollowRequest,
  FollowedCategoriesResponse,
  IgnoredStreamersResponse,
  PushIgnoreStreamerRequest,
  PushRegisterRequest,
  PushUnregisterRequest,
  RemoveIgnoredStreamerRequest,
  StreamNotification,
  TagFiltersResponse,
  TopGameIdsResponse,
}

class ApiRoutes(
  clientId: String,
  followRepo: FollowRepository,
  ignoredStreamerRepo: IgnoredStreamerRepository,
  notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]],
  pushRepo: PushSubscriptionRepository,
  sessionManager: SessionManager,
  sessionRepo: SessionRepository,
  settings: AppSettings,
  tagFilterRepo: TagFilterRepository,
  topGamesRepo: TopGamesRepository,
  twitchApi: TwitchApi,
) {

  private object SearchQueryParamMatcher extends QueryParamDecoderMatcher[String]("query")
  private object AfterQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("after")

  private def withSession(
    req: Request[IO],
  )(f: SessionData => IO[Response[IO]]): IO[Response[IO]] =
    sessionManager.getSession(req).flatMap {
      case Some(data) => f(data)
      case None => Forbidden("Not logged in")
    }

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "config" =>
      Ok(AppConfig(clientId))

    case req @ GET -> Root / "user" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) => Ok(data.user)
        case None => NotFound("Not logged in")
      }

    case req @ GET -> Root / "followed" =>
      withSession(req) { data =>
        followRepo.getFollowed(data.user.id).flatMap(cats => Ok(FollowedCategoriesResponse(cats)))
      }

    case req @ POST -> Root / "follow" =>
      withSession(req) { data =>
        req.as[FollowRequest].flatMap { followReq =>
          followRepo.follow(data.user.id, followReq.category) *> Ok("Followed")
        }
      }

    case req @ POST -> Root / "unfollow" / categoryId =>
      withSession(req) { data =>
        followRepo.unfollow(data.user.id, categoryId) *> Ok("Unfollowed")
      }

    case req @ GET -> Root / "search" / "categories" :? SearchQueryParamMatcher(
          query,
        ) +& AfterQueryParamMatcher(after) =>
      withSession(req) { data =>
        sessionManager.refreshTokenIfNeeded(data).flatMap { refreshed =>
          twitchApi
            .searchCategories(query, after, refreshed.accessToken, settings.searchPageSize)
            .flatMap(Ok(_))
        }
      }

    case req @ GET -> Root / "search" / "channels" :? SearchQueryParamMatcher(
          query,
        ) +& AfterQueryParamMatcher(after) =>
      withSession(req) { data =>
        sessionManager.refreshTokenIfNeeded(data).flatMap { refreshed =>
          twitchApi
            .searchChannels(query, after, refreshed.accessToken, settings.searchPageSize)
            .flatMap(Ok(_))
        }
      }

    case req @ POST -> Root / "logout" =>
      val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
      for {
        _ <- sessionId.fold(IO.unit)(id => sessionRepo.deleteSession(id))
        res <- Ok("Logged out").map(_.removeCookie("session_id"))
      } yield res

    case req @ GET -> Root / "tag-filters" =>
      withSession(req) { data =>
        tagFilterRepo
          .getTagFilters(data.user.id)
          .flatMap(filters => Ok(TagFiltersResponse(filters)))
      }

    case req @ POST -> Root / "tag-filters" / "add" =>
      withSession(req) { data =>
        req.as[AddTagFilterRequest].flatMap { body =>
          (
            Validation.validateTag(body.tag),
            Validation.validateFilterType(body.filterType),
          ) match {
            case (Right(tag), Right(ft)) =>
              tagFilterRepo.addTagFilter(data.user.id, ft, tag) *> Ok("Filter added")
            case (Left(err), _) => BadRequest(err)
            case (_, Left(err)) => BadRequest(err)
          }
        }
      }

    case req @ POST -> Root / "tag-filters" / "remove" =>
      withSession(req) { data =>
        req.as[AddTagFilterRequest].flatMap { body =>
          tagFilterRepo.removeTagFilter(data.user.id, body.filterType, body.tag) *> Ok(
            "Filter removed",
          )
        }
      }

    case req @ GET -> Root / "ignored-streamers" =>
      withSession(req) { data =>
        ignoredStreamerRepo
          .getIgnoredStreamers(data.user.id)
          .flatMap(streamers => Ok(IgnoredStreamersResponse(streamers)))
      }

    case req @ POST -> Root / "ignored-streamers" / "add" =>
      withSession(req) { data =>
        req.as[AddIgnoredStreamerRequest].flatMap { body =>
          Validation.validateNonEmpty(body.streamerId, "streamerId") match {
            case Right(_) =>
              ignoredStreamerRepo.addIgnoredStreamer(
                data.user.id,
                body.streamerId,
                body.streamerLogin,
                body.streamerName,
              ) *> Ok("Streamer ignored")
            case Left(err) => BadRequest(err)
          }
        }
      }

    case req @ POST -> Root / "ignored-streamers" / "remove" =>
      withSession(req) { data =>
        req.as[RemoveIgnoredStreamerRequest].flatMap { body =>
          ignoredStreamerRepo
            .removeIgnoredStreamer(data.user.id, body.streamerId) *> Ok("Streamer unignored")
        }
      }

    case req @ POST -> Root / "push" / "register" =>
      withSession(req) { data =>
        req.as[PushRegisterRequest].flatMap { body =>
          Validation.validatePlatform(body.platform) match {
            case Right(platform) =>
              pushRepo.savePushSubscription(data.user.id, body.token, platform) *> Ok("Registered")
            case Left(err) => BadRequest(err)
          }
        }
      }

    case req @ POST -> Root / "push" / "unregister" =>
      withSession(req) { _ =>
        req.as[PushUnregisterRequest].flatMap { body =>
          pushRepo.deletePushSubscription(body.token) *> Ok("Unregistered")
        }
      }

    // Authenticated by the device push token rather than the session cookie, so that
    // native notification action handlers (which run outside the WebView and can't read
    // the session_id cookie) can ignore a streamer in the background.
    case req @ POST -> Root / "push" / "ignore-streamer" =>
      req.as[PushIgnoreStreamerRequest].flatMap { body =>
        Validation.validateNonEmpty(body.streamerId, "streamerId") match {
          case Right(_) =>
            pushRepo.getUserIdByToken(body.token).flatMap {
              case Some(userId) =>
                ignoredStreamerRepo.addIgnoredStreamer(
                  userId = userId,
                  streamerId = body.streamerId,
                  streamerLogin = body.streamerLogin,
                  streamerName = body.streamerName,
                ) *> Ok("Streamer ignored")
              case None => Forbidden("Unknown device token")
            }
          case Left(err) => BadRequest(err)
        }
      }

    case req @ GET -> Root / "top-game-ids" =>
      withSession(req) { _ =>
        topGamesRepo.getTopGameIds.flatMap(ids => Ok(TopGameIdsResponse(ids)))
      }

    case req @ GET -> Root / "notifications" / "stream" =>
      withSession(req) { data =>
        val sessionId =
          req.cookies.find(_.name == "session_id").map(_.content).getOrElse("unknown")
        Queue.unbounded[IO, StreamNotification].flatMap { queue =>
          notificationQueues.update(_ + (sessionId -> (data.user.id, queue))) *> {
            val eventStream: fs2.Stream[IO, ServerSentEvent] =
              fs2
                .Stream
                .fromQueueUnterminated(queue)
                .map { n =>
                  ServerSentEvent(
                    data = Some(n.asJson.noSpaces),
                    eventType = Some("stream-live"),
                  )
                }
                .onFinalize(notificationQueues.update(_ - sessionId))
            Ok(eventStream)
          }
        }
      }
  }

}
