package com.twitch.core

import io.circe.Codec

case class TwitchTokenResponse(
    access_token: String,
    expires_in: Int,
    refresh_token: Option[String],
    scope: Option[List[String]],
    token_type: String
) derives Codec.AsObject

case class TwitchUser(
    id: String,
    login: String,
    display_name: String,
    profile_image_url: String,
    email: Option[String] = None
) derives Codec.AsObject

case class TwitchUsersResponse(
    data: List[TwitchUser]
) derives Codec.AsObject

case class TwitchCategory(
    id: String,
    name: String,
    box_art_url: String
) derives Codec.AsObject

case class TwitchPagination(
    cursor: Option[String]
) derives Codec.AsObject

case class TwitchSearchCategoriesResponse(
    data: List[TwitchCategory],
    pagination: Option[TwitchPagination]
) derives Codec.AsObject

case class TwitchChannel(
    id: String,
    broadcaster_login: String,
    display_name: String,
    thumbnail_url: String,
    is_live: Boolean
) derives Codec.AsObject

case class TwitchSearchChannelsResponse(
    data: List[TwitchChannel],
    pagination: Option[TwitchPagination]
) derives Codec.AsObject

case class AppConfig(
    twitchClientId: String
) derives Codec.AsObject

case class FollowRequest(category: TwitchCategory) derives Codec.AsObject

case class FollowedCategoriesResponse(categories: List[TwitchCategory]) derives Codec.AsObject

case class TwitchStream(
    id: String,
    user_id: String,
    user_login: String,
    user_name: String,
    game_id: String,
    game_name: String,
    `type`: String,
    title: String,
    viewer_count: Int,
    started_at: String,
    thumbnail_url: String,
    tags: Option[List[String]] = None
) derives Codec.AsObject

case class TwitchStreamsResponse(
    data: List[TwitchStream],
    pagination: Option[TwitchPagination]
) derives Codec.AsObject

case class StreamNotification(
    categoryId: String,
    categoryName: String,
    streamerId: String,
    streamerLogin: String,
    streamerName: String,
    streamTitle: String,
    viewerCount: Int,
    thumbnailUrl: String,
    tags: List[String] = Nil
) derives Codec.AsObject

case class TagFilter(
    filterType: String,
    tag: String
) derives Codec.AsObject

case class TagFiltersResponse(
    filters: List[TagFilter]
) derives Codec.AsObject

case class AddTagFilterRequest(
    filterType: String,
    tag: String
) derives Codec.AsObject

case class IgnoredStreamer(
    streamerId: String,
    streamerLogin: String,
    streamerName: String
) derives Codec.AsObject

case class IgnoredStreamersResponse(
    streamers: List[IgnoredStreamer]
) derives Codec.AsObject

case class AddIgnoredStreamerRequest(
    streamerId: String,
    streamerLogin: String,
    streamerName: String
) derives Codec.AsObject

case class RemoveIgnoredStreamerRequest(
    streamerId: String
) derives Codec.AsObject

case class PushRegisterRequest(
    token: String,
    platform: String
) derives Codec.AsObject

case class PushUnregisterRequest(
    token: String
) derives Codec.AsObject
