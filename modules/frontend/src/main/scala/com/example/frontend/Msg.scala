package com.example.frontend

import com.example.core.{TwitchUser, AppConfig, TwitchCategory, TwitchSearchCategoriesResponse, FollowedCategoriesResponse}
import tyrian.http.*
import io.circe.parser.decode

enum Msg:
  case GotUser(user: TwitchUser)
  case GotConfig(config: AppConfig)
  case ApiError(error: String)
  case LoginWithTwitch
  case Logout
  case UpdateSearchQuery(query: String)
  case SearchCategories
  case GotSearchResults(results: List[TwitchCategory])
  case ToggleCategorySelection(id: String)
  case FollowCategory(category: TwitchCategory)
  case UnfollowCategory(id: String)
  case GotFollowedCategories(categories: List[TwitchCategory])
  case FollowActionSuccess
  case NoOp

object Msg:
  def fromUserResponse: Decoder[Msg] = Decoder(
    response =>
      response.status match
        case Status(code, _) if code >= 200 && code < 300 =>
          decode[TwitchUser](response.body) match
            case Right(user) => Msg.GotUser(user)
            case Left(_)     => Msg.NoOp
        case _ => Msg.NoOp,
    _ => Msg.NoOp
  )

  def fromConfigResponse: Decoder[Msg] = Decoder(
    response =>
      response.status match
        case Status(code, _) if code >= 200 && code < 300 =>
          decode[AppConfig](response.body) match
            case Right(config) => Msg.GotConfig(config)
            case Left(err)     => Msg.ApiError(s"Config decoding error: ${err.getMessage}")
        case Status(code, msg) =>
          Msg.ApiError(s"Server returned $code: $msg"),
    error => Msg.ApiError(s"Config fetch network error: ${error.toString}")
  )

  def fromSearchResponse: Decoder[Msg] = Decoder(
    response =>
      response.status match
        case Status(code, _) if code >= 200 && code < 300 =>
          decode[TwitchSearchCategoriesResponse](response.body) match
            case Right(res) => Msg.GotSearchResults(res.data)
            case Left(err)  => Msg.ApiError(s"Search decoding error: ${err.getMessage}")
        case Status(code, msg) =>
          Msg.ApiError(s"Search failed with $code: $msg"),
    error => Msg.ApiError(s"Search network error: ${error.toString}")
  )

  def fromLogoutResponse: Decoder[Msg] = Decoder(
    _ => Msg.NoOp,
    _ => Msg.NoOp
  )

  def fromFollowedResponse: Decoder[Msg] = Decoder(
    response =>
      response.status match
        case Status(code, _) if code >= 200 && code < 300 =>
          decode[FollowedCategoriesResponse](response.body) match
            case Right(res) => Msg.GotFollowedCategories(res.categories)
            case Left(_)    => Msg.NoOp
        case _ => Msg.NoOp,
    _ => Msg.NoOp
  )

  def fromFollowActionResponse: Decoder[Msg] = Decoder(
    _ => Msg.FollowActionSuccess,
    error => Msg.ApiError(s"Action failed: ${error.toString}")
  )
