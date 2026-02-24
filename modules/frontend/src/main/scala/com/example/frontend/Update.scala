package com.example.frontend

import tyrian.*
import tyrian.http.*
import cats.effect.IO
import com.example.core.FollowRequest
import io.circe.syntax.*

object Update:
  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.LoginWithTwitch =>
      model.twitchClientId match
        case Some(clientId) =>
          val redirectUri = "http://localhost:8080/auth/callback"
          val scope = "user:read:email"
          val url = s"https://id.twitch.tv/oauth2/authorize?client_id=$clientId&redirect_uri=$redirectUri&response_type=code&scope=$scope"
          (model, Nav.loadUrl(url))
        case None =>
          (model.copy(status = Some("Error: Twitch Client ID not loaded yet")), Cmd.None)
    case Msg.GotUser(user) =>
      (model.copy(user = Some(user)), Cmd.None)
    case Msg.GotConfig(config) =>
      (model.copy(twitchClientId = Some(config.twitchClientId)), Cmd.None)
    case Msg.Logout =>
      (model.copy(user = None, status = Some("Logging out...")), Http.send(Request.post("/api/logout", Body.Empty), Msg.fromLogoutResponse))
    case Msg.UpdateSearchQuery(q) =>
      (model.copy(searchQuery = q), Cmd.None)
    case Msg.SearchCategories =>
      if (model.searchQuery.trim.isEmpty) (model, Cmd.None)
      else (model.copy(status = Some("Searching...")), Http.send(Request.get(s"/api/search/categories?query=${model.searchQuery}"), Msg.fromSearchResponse))
    case Msg.GotSearchResults(results) =>
      (model.copy(searchResults = results, status = None), Cmd.None)
    case Msg.ToggleCategorySelection(id) =>
      val newSelection = 
        if (model.selectedCategoryIds.contains(id)) model.selectedCategoryIds - id
        else model.selectedCategoryIds + id
      (model.copy(selectedCategoryIds = newSelection), Cmd.None)
    case Msg.FollowCategory(cat) =>
      (model.copy(status = Some(s"Following ${cat.name}...")), 
       Http.send(
         Request.post("/api/follow", Body.json(FollowRequest(cat).asJson.noSpaces)), 
         Msg.fromFollowActionResponse
       ))
    case Msg.UnfollowCategory(id) =>
      (model.copy(status = Some("Unfollowing...")), 
       Http.send(
         Request.post(s"/api/unfollow/$id", Body.Empty), 
         Msg.fromFollowActionResponse
       ))
    case Msg.GotFollowedCategories(cats) =>
      (model.copy(followedCategories = cats, status = None), Cmd.None)
    case Msg.FollowActionSuccess =>
      (model, Http.send(Request.get("/api/followed"), Msg.fromFollowedResponse))
    case Msg.ApiError(error) =>
      (model.copy(status = Some(s"Error: $error")), Cmd.None)
    case Msg.NoOp =>
      (model, Cmd.None)
