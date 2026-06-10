package com.twitch.backend

import scala.concurrent.duration.*

import cats.effect.IO
import com.typesafe.config.ConfigFactory

case class AppSettings(
  emailFrom: String,
  emailFromName: String,
  maxFollowedCategories: Int,
  maxIgnoredStreamers: Int,
  maxPushSubscriptions: Int,
  maxTagFilters: Int,
  oauthStateTtl: FiniteDuration,
  parallelCategories: Int,
  pollerInterval: FiniteDuration,
  pushActionTokenTtl: FiniteDuration,
  pushParallelSends: Int,
  recentlyLiveWindow: FiniteDuration,
  searchPageSize: Int,
  sessionTtl: FiniteDuration,
  sseMaxConnections: Int,
  sseMaxConnectionsPerUser: Int,
  sseQueueCapacity: Int,
  sseReconnectDelay: FiniteDuration,
  streamsPageSize: Int,
  tokenRefreshSkew: FiniteDuration,
  topGamesCount: Int,
  topGamesPollInterval: FiniteDuration,
)

object AppSettings {

  def load: IO[AppSettings] = IO.blocking {
    val config = ConfigFactory.load().getConfig("twitch-app")
    AppSettings(
      emailFrom = config.getString("email.from"),
      emailFromName = config.getString("email.from-name"),
      maxFollowedCategories = config.getInt("limits.max-followed-categories"),
      maxIgnoredStreamers = config.getInt("limits.max-ignored-streamers"),
      maxPushSubscriptions = config.getInt("limits.max-push-subscriptions"),
      maxTagFilters = config.getInt("limits.max-tag-filters"),
      oauthStateTtl = config.getDuration("oauth.state-ttl").toMillis.millis,
      parallelCategories = config.getInt("poller.parallel-categories"),
      pollerInterval = config.getDuration("poller.interval").toMillis.millis,
      pushActionTokenTtl = config.getDuration("push.action-token-ttl").toMillis.millis,
      pushParallelSends = config.getInt("push.parallel-sends"),
      recentlyLiveWindow = config.getDuration("poller.recently-live-window").toMillis.millis,
      searchPageSize = config.getInt("search.page-size"),
      sessionTtl = config.getDuration("session.ttl").toMillis.millis,
      sseMaxConnections = config.getInt("sse.max-connections"),
      sseMaxConnectionsPerUser = config.getInt("sse.max-connections-per-user"),
      sseQueueCapacity = config.getInt("sse.queue-capacity"),
      sseReconnectDelay = config.getDuration("sse.reconnect-delay").toMillis.millis,
      streamsPageSize = config.getInt("poller.streams-page-size"),
      tokenRefreshSkew = config.getDuration("auth.token-refresh-skew").toMillis.millis,
      topGamesCount = config.getInt("top-games.count"),
      topGamesPollInterval = config.getDuration("top-games.poll-interval").toMillis.millis,
    )
  }

}
