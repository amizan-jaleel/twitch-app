package com.example.core

import io.circe.Codec

case class Ping(message: String) derives Codec.AsObject

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
    profile_image_url: String
) derives Codec.AsObject

case class TwitchUsersResponse(
    data: List[TwitchUser]
) derives Codec.AsObject

case class AppConfig(
    twitchClientId: String
) derives Codec.AsObject
