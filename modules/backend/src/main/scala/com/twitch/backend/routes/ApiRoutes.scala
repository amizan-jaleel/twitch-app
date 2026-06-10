package com.twitch.backend.routes

import cats.effect.*
import cats.effect.std.Queue
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*

import com.twitch.backend.{AppSettings, PushActionTokenService, TwitchApi, Validation}
import com.twitch.backend.auth.{SessionData, SessionManager}
import com.twitch.backend.db.{
  FollowRepository,
  IgnoredStreamerRepository,
  PushSubscriptionRepository,
  PushSubscriptionSaveResult,
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
  pushActionTokens: PushActionTokenService,
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

  private def tooMany(resource: String): IO[Response[IO]] =
    TooManyRequests(s"Too many $resource. Remove an existing item before adding another.")

  private def withSession(
    req: Request[IO],
  )(f: SessionData => IO[Response[IO]]): IO[Response[IO]] =
    sessionManager.getSession(req).flatMap {
      case Some(data) => f(data)
      case None => Forbidden("Not logged in")
    }

  private def registerNotificationQueue(
    userId: String,
    sessionId: String,
    queue: Queue[IO, StreamNotification],
  ): IO[Boolean] =
    notificationQueues.modify { queues =>
      val replacingExistingSession = queues.contains(sessionId)
      val userConnectionCount = queues.values.count(_._1 == userId)
      val canRegister = replacingExistingSession || (
        queues.size < settings.sseMaxConnections &&
          userConnectionCount < settings.sseMaxConnectionsPerUser
      )
      if canRegister then (queues + (sessionId -> (userId, queue)), true)
      else (queues, false)
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
          followRepo
            .followIfUnderLimit(data.user.id, followReq.category, settings.maxFollowedCategories)
            .flatMap {
              case true => Ok("Followed")
              case false => tooMany("followed categories")
            }
        }
      }

    case req @ POST -> Root / "unfollow" / categoryId =>
      withSession(req)(data => followRepo.unfollow(data.user.id, categoryId) *> Ok("Unfollowed"))

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
              tagFilterRepo
                .addTagFilterIfUnderLimit(data.user.id, ft, tag, settings.maxTagFilters)
                .flatMap {
                  case true => Ok("Filter added")
                  case false => tooMany("tag filters")
                }
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
            case Right(streamerId) =>
              ignoredStreamerRepo
                .addIgnoredStreamerIfUnderLimit(
                  data.user.id,
                  streamerId,
                  body.streamerLogin,
                  body.streamerName,
                  settings.maxIgnoredStreamers,
                )
                .flatMap {
                  case true => Ok("Streamer ignored")
                  case false => tooMany("ignored streamers")
                }
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
          (
            Validation.validateNonEmpty(body.token, "token"),
            Validation.validatePlatform(body.platform),
          ) match {
            case (Right(token), Right(platform)) =>
              pushRepo
                .savePushSubscriptionIfUnderLimit(
                  data.user.id,
                  token,
                  platform,
                  settings.maxPushSubscriptions,
                )
                .flatMap {
                  case PushSubscriptionSaveResult.Saved => Ok("Registered")
                  case PushSubscriptionSaveResult.LimitReached => tooMany("push subscriptions")
                  case PushSubscriptionSaveResult.TokenOwnedByAnotherUser =>
                    Conflict("Push token is already registered to another user")
                }
            case (Left(err), _) => BadRequest(err)
            case (_, Left(err)) => BadRequest(err)
          }
        }
      }

    case req @ POST -> Root / "push" / "unregister" =>
      withSession(req) { data =>
        req.as[PushUnregisterRequest].flatMap { body =>
          Validation.validateNonEmpty(body.token, "token") match {
            case Right(token) =>
              pushRepo.deletePushSubscription(data.user.id, token) *> Ok("Unregistered")
            case Left(err) => BadRequest(err)
          }
        }
      }

    // Native notification actions cannot read the WebView session cookie, so the push payload
    // carries a short-lived signed action token scoped to one user and streamer.
    case req @ POST -> Root / "push" / "ignore-streamer" =>
      req.as[PushIgnoreStreamerRequest].flatMap { body =>
        pushActionTokens.verifyIgnoreStreamerToken(body.actionToken) match {
          case Right(claims) =>
            ignoredStreamerRepo
              .addIgnoredStreamerIfUnderLimit(
                userId = claims.userId,
                streamerId = claims.streamerId,
                streamerLogin = claims.streamerLogin,
                streamerName = claims.streamerName,
                maxIgnoredStreamers = settings.maxIgnoredStreamers,
              )
              .flatMap {
                case true => Ok("Streamer ignored")
                case false => tooMany("ignored streamers")
              }
          case Left(err) => Forbidden(err)
        }
      }

    case req @ GET -> Root / "top-game-ids" =>
      withSession(req)(_ => topGamesRepo.getTopGameIds.flatMap(ids => Ok(TopGameIdsResponse(ids))))

    case req @ GET -> Root / "notifications" / "stream" =>
      withSession(req) { data =>
        val sessionId =
          req.cookies.find(_.name == "session_id").map(_.content).getOrElse("unknown")
        Queue.bounded[IO, StreamNotification](settings.sseQueueCapacity).flatMap { queue =>
          registerNotificationQueue(data.user.id, sessionId, queue).flatMap {
            case false => tooMany("notification streams")
            case true =>
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
                  .onFinalize(
                    notificationQueues.update { queues =>
                      queues.get(sessionId) match {
                        case Some((_, currentQueue)) if currentQueue eq queue => queues - sessionId
                        case _ => queues
                      }
                    },
                  )
              Ok(eventStream)
          }
        }
      }
  }

}
