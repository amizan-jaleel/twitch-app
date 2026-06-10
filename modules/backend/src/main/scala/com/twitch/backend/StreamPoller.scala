package com.twitch.backend

import java.time.Instant

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.implicits.*

import com.twitch.backend.db.{
  FollowRepository,
  IgnoredStreamerRepository,
  PushSubscriptionRepository,
  TagFilterRepository,
}
import com.twitch.core.{StreamNotification, TwitchStream, TwitchStreamsResponse}

class StreamPoller(
  appToken: Ref[IO, Option[AppAccessToken]],
  client: Client[IO],
  clientId: String,
  clientSecret: String,
  followRepo: FollowRepository,
  ignoredStreamerRepo: IgnoredStreamerRepository,
  notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]],
  notifiedStreamIds: Ref[IO, Set[String]],
  pushRepo: PushSubscriptionRepository,
  pushService: Option[PushService],
  settings: AppSettings,
  tagFilterRepo: TagFilterRepository,
) extends TwitchPoller(clientId, clientSecret, client, appToken) {

  private def fetchStreamsPage(
    categoryId: String,
    cursor: Option[String],
  )(using AppAccessToken): IO[TwitchStreamsResponse] = {
    val baseUri = uri"https://api.twitch.tv/helix/streams"
      .withQueryParam("game_id", categoryId)
      .withQueryParam("first", settings.streamsPageSize.toString)
    client.expect[TwitchStreamsResponse](buildAuthedRequest(baseUri, cursor))
  }

  private def fetchLiveStreams(categoryIds: List[String])(using
    AppAccessToken,
  ): IO[List[TwitchStream]] =
    categoryIds
      .parTraverseN(settings.parallelCategories) { categoryId =>
        fetchPaginated[TwitchStream](fetchStreamsPage(categoryId, _))
      }
      .map(_.flatten)

  private def loadNotificationCriteria(
    userIds: Set[String],
  ): IO[Map[String, NotificationCriteria]] =
    userIds
      .toList
      .traverse { uid =>
        for {
          followed <- followRepo.getFollowed(uid).map(_.map(_.id).toSet)
          filters <- tagFilterRepo.getTagFilters(uid)
          ignored <- ignoredStreamerRepo.getIgnoredStreamers(uid).map(_.map(_.streamerId).toSet)
        } yield uid -> NotificationCriteria(
          followed = followed,
          filters = filters,
          ignored = ignored,
        )
      }
      .map(_.toMap)

  private def broadcastNotifications(notifications: List[StreamNotification]): IO[Unit] = {
    val byCategoryId = notifications.groupBy(_.categoryId)
    for {
      // SSE delivery: scoped to connected users
      queues <- notificationQueues.get
      sseUserIds = queues.values.map(_._1).toSet
      sseCriteria <- loadNotificationCriteria(sseUserIds)
      _ <- queues.values.toList.traverse_ {
        case (userId, queue) =>
          val filtered = StreamLogic.filteredNotificationsForUser(
            userId,
            byCategoryId,
            sseCriteria,
          )
          filtered.traverse_(queue.tryOffer(_).void)
      }
      // Push delivery: database-driven, independent of SSE connections
      _ <- pushService.fold(IO.unit) { ps =>
        (for {
          pushUserIds <- followRepo.getUsersFollowingCategories(byCategoryId.keySet)
          pushCriteria <- loadNotificationCriteria(pushUserIds)
          _ <- sendPushNotifications(ps, notifications, pushCriteria)
        } yield ())
          .handleErrorWith(e => IO.println(s"Push notification error: ${e.getMessage}"))
          .start
          .void
      }
    } yield ()
  }

  private def sendPushNotifications(
    ps: PushService,
    notifications: List[StreamNotification],
    criteriaByUser: Map[String, NotificationCriteria],
  ): IO[Unit] = {
    val byCategoryId = notifications.groupBy(_.categoryId)
    val allFollowingUserIds = criteriaByUser.filter {
      case (_, c) => c.followed.exists(byCategoryId.contains)
    }.keySet
    pushRepo
      .getPushSubscriptionsForUsers(allFollowingUserIds)
      .flatMap { subs =>
        val subsByUser = subs.groupBy(_.userId)
        subsByUser.toList.traverse_ {
          case (userId, userSubs) =>
            val filtered = StreamLogic.filteredNotificationsForUser(
              userId,
              byCategoryId,
              criteriaByUser,
            )
            if filtered.nonEmpty then ps.sendBatch(userSubs, filtered)
            else IO.unit
        }
      }
      .handleErrorWith(e => IO.println(s"Push notification error: ${e.getMessage}"))
  }

  // First poll seeds the set without sending notifications so we don't
  // flood the user with every stream that happens to be live at startup.
  private def seedOnce: IO[Unit] =
    for {
      allCategories <- followRepo.getAllFollowedCategories
      _ <- IO.whenA(allCategories.nonEmpty) {
        for {
          streams <- withTokenRefresh(fetchLiveStreams(allCategories.map(_.id)))
          liveIds = streams.collect { case s if s.`type` == "live" => s.id }.toSet
          _ <- notifiedStreamIds.set(liveIds)
          _ <- IO.println(
            s"Poller: seeded ${liveIds.size} already-live streams across ${allCategories.size} categories",
          )
        } yield ()
      }
    } yield ()

  private def pollOnce: IO[Unit] =
    for {
      allCategories <- followRepo.getAllFollowedCategories
      _ <- IO.whenA(allCategories.nonEmpty) {
        for {
          streams <- withTokenRefresh(fetchLiveStreams(allCategories.map(_.id)))
          now <- IO(Instant.now())
          alreadyNotified <- notifiedStreamIds.get
          (newStreams, updatedNotified) = StreamLogic.findNewStreams(
            streams,
            alreadyNotified,
            now,
            settings.recentlyLiveWindow,
          )
          _ <- notifiedStreamIds.set(updatedNotified)
          _ <- IO.println(
            s"Poller: fetched ${streams.size} total streams across ${allCategories.size} categories, ${newStreams.size} new",
          )
          _ <- IO.whenA(newStreams.nonEmpty) {
            broadcastNotifications(newStreams.map(StreamLogic.toNotification))
          }
        } yield ()
      }
    } yield ()

  def start: IO[Nothing] =
    IO.println(s"StreamPoller: starting (polling every ${settings.pollerInterval.toSeconds}s)") *>
      seedOnce.handleErrorWith(e => IO.println(s"StreamPoller seed error: $e")) *>
      (IO.sleep(settings.pollerInterval) *> pollOnce.handleErrorWith(e =>
        IO.println(s"StreamPoller error: $e"),
      )).foreverM

}

object StreamPoller {

  def make(
    client: Client[IO],
    clientId: String,
    clientSecret: String,
    followRepo: FollowRepository,
    ignoredStreamerRepo: IgnoredStreamerRepository,
    notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]],
    pushRepo: PushSubscriptionRepository,
    pushService: Option[PushService] = None,
    settings: AppSettings,
    tagFilterRepo: TagFilterRepository,
  ): IO[StreamPoller] =
    for {
      tokenRef <- IO.ref(Option.empty[AppAccessToken])
      notifiedRef <- IO.ref(Set.empty[String])
    } yield new StreamPoller(
      appToken = tokenRef,
      client = client,
      clientId = clientId,
      clientSecret = clientSecret,
      followRepo = followRepo,
      ignoredStreamerRepo = ignoredStreamerRepo,
      notificationQueues = notificationQueues,
      notifiedStreamIds = notifiedRef,
      pushRepo = pushRepo,
      pushService = pushService,
      settings = settings,
      tagFilterRepo = tagFilterRepo,
    )

}
