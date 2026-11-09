package com.twitch.backend

import cats.effect.IO

import com.twitch.backend.db.PushSubscriptionRow
import com.twitch.core.{
  StreamNotification,
  TwitchCategory,
  TwitchSearchCategoriesResponse,
  TwitchSearchChannelsResponse,
  TwitchStream,
  TwitchTokenResponse,
  TwitchUser,
}

trait PushService {

  def sendBatch(
    subscriptions: List[PushSubscriptionRow],
    notifications: List[StreamNotification],
  ): IO[Unit]

}

trait EmailNotifier {
  def sendWelcomeEmail(email: String, displayName: String): IO[Unit]
}

trait TwitchApi {

  def searchCategories(
    query: String,
    after: Option[String],
    accessToken: String,
    pageSize: Int,
  ): IO[TwitchSearchCategoriesResponse]

  def searchChannels(
    query: String,
    after: Option[String],
    accessToken: String,
    pageSize: Int,
  ): IO[TwitchSearchChannelsResponse]

  def getUser(accessToken: String): IO[TwitchUser]
  def exchangeCode(code: String, redirectUri: String): IO[TwitchTokenResponse]
  def refreshToken(refreshToken: String): IO[TwitchTokenResponse]
}

trait TwitchPollingApi {
  def liveStreams(categoryIds: List[String]): IO[List[TwitchStream]]
  def topGames(limit: Int): IO[List[TwitchCategory]]
}
