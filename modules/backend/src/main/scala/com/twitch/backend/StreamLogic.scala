package com.twitch.backend

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import com.twitch.core.*

object StreamLogic:

  def toNotification(s: TwitchStream): StreamNotification =
    StreamNotification(
      categoryId = s.game_id,
      categoryName = s.game_name,
      streamerId = s.user_id,
      streamerLogin = s.user_login,
      streamerName = s.user_name,
      streamTitle = s.title,
      viewerCount = s.viewer_count,
      thumbnailUrl = s.thumbnail_url.replace("{width}", "320").replace("{height}", "180"),
      tags = s.tags.getOrElse(Nil)
    )

  def recentlyWentLive(s: TwitchStream, now: Instant, window: FiniteDuration): Boolean =
    s.`type` == "live" && {
      val startedAt = Instant.parse(s.started_at)
      java.time.Duration.between(startedAt, now).toMillis < window.toMillis
    }

  /** Given all fetched streams, return the ones that are newly live
    * (recently started AND not already notified). Also returns the
    * updated set of notified IDs (all seen stream IDs, not just new
    * ones) to prevent re-notification from pagination flicker.
    */
  def findNewStreams(
      allStreams: List[TwitchStream],
      alreadyNotified: Set[String],
      now: Instant,
      recentWindow: FiniteDuration
  ): (List[TwitchStream], Set[String]) =
    val recentStreams = allStreams.filter(recentlyWentLive(_, now, recentWindow))
    val newStreams = recentStreams.filter(s => !alreadyNotified.contains(s.id))
    val updatedNotified = alreadyNotified | allStreams.map(_.id).toSet
    (newStreams, updatedNotified)

  def applyTagFilters(
      notifications: List[StreamNotification],
      filters: List[TagFilter]
  ): List[StreamNotification] =
    val (includes, excludes) = filters.partition(_.filterType == "include")
    val includeTags = includes.map(_.tag.toLowerCase).toSet
    val excludeTags = excludes.map(_.tag.toLowerCase).toSet
    notifications.filter { n =>
      val streamTags = n.tags.map(_.toLowerCase).toSet
      val passesInclude = includeTags.isEmpty || streamTags.exists(includeTags.contains)
      val passesExclude = !streamTags.exists(excludeTags.contains)
      passesInclude && passesExclude
    }

  def applyIgnoredStreamers(
      notifications: List[StreamNotification],
      ignoredStreamerIds: Set[String]
  ): List[StreamNotification] =
    if ignoredStreamerIds.isEmpty then notifications
    else notifications.filterNot(n => ignoredStreamerIds.contains(n.streamerId))
