package com.example.frontend

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import tyrian.Html.*
import tyrian.*
import tyrian.http.*
import scala.scalajs.js.annotation.*
import com.example.core.{TwitchUser, AppConfig, TwitchCategory, TwitchSearchCategoriesResponse, FollowRequest, FollowedCategoriesResponse}
import io.circe.parser.decode
import io.circe.syntax.*

@JSExportTopLevel("TyrianApp")
object Main extends TyrianApp[IO, Msg, Model]:

  def router: Location => Msg =
    _ => Msg.NoOp

  val run: IO[Nothing] => Unit =
    _.unsafeRunAndForget()

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model(None, None, None, "", Nil, Set.empty, Nil), 
     Cmd.Batch(
       Http.send(Request.get("/api/user"), Msg.fromUserResponse),
       Http.send(Request.get("/api/config"), Msg.fromConfigResponse),
       Http.send(Request.get("/api/followed"), Msg.fromFollowedResponse)
     ))

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

  def view(model: Model): Html[Msg] =
    div(
      h1("Twitch App"),
      div(
        if (model.user.isEmpty)
          button(onClick(Msg.LoginWithTwitch), style("background", "#9146ff"))("Login with Twitch")
        else
          span()
      ),
      model.status.map(s => p(style("font-weight", "bold"))(s)).getOrElse(div()),
      
      model.user.map(u => div(
        h2(s"Welcome, ${u.display_name}!"),
        img(src := u.profile_image_url, style("border-radius", "50%"), style("width", "50px")),
        span(" "),
        button(onClick(Msg.Logout), style("background", "#ff4646"))("Logout"),
        
        hr(),
        
        h3("Search Categories"),
        div(
          input(
            `type` := "text",
            placeholder := "Search for a category...",
            value := model.searchQuery,
            onInput(q => Msg.UpdateSearchQuery(q))
          ),
          button(onClick(Msg.SearchCategories))("Search")
        ),
        
        div(style("display", "flex"), style("flex-wrap", "wrap"), style("justify-content", "center"), style("margin-top", "20px"))(
          model.searchResults.map { cat =>
            val isSelected = model.selectedCategoryIds.contains(cat.id)
            val isFollowed = model.followedCategories.exists(_.id == cat.id)
            val boxArtUrl = cat.box_art_url.replace("{width}", "140").replace("{height}", "185")
            
            div(
              style("margin", "10px"),
              style("padding", "10px"),
              style("border", if (isSelected) "2px solid #9146ff" else "1px solid #ddd"),
              style("border-radius", "8px"),
              style("width", "160px"),
              style("background", if (isSelected) "#f0e6ff" else "white")
            )(
              div(
                onClick(Msg.ToggleCategorySelection(cat.id)),
                style("cursor", "pointer")
              )(
                img(src := boxArtUrl, style("width", "140px"), style("height", "185px"), style("border-radius", "4px")),
                p(style("font-size", "0.9rem"), style("font-weight", "bold"), style("margin", "5px 0"))(cat.name)
              ),
              if (isFollowed)
                button(onClick(Msg.UnfollowCategory(cat.id)), style("background", "#ff4646"), style("width", "100%"), style("margin-top", "5px"))("Unfollow")
              else
                button(onClick(Msg.FollowCategory(cat)), style("background", "#9146ff"), style("width", "100%"), style("margin-top", "5px"))("Follow")
            )
          }
        ),
        
        hr(),
        h3("Your Followed Categories"),
        if (model.followedCategories.isEmpty)
          p("You haven't followed any categories yet.")
        else
          div(style("display", "flex"), style("flex-wrap", "wrap"), style("justify-content", "center"))(
            model.followedCategories.map { cat =>
              val boxArtUrl = cat.box_art_url.replace("{width}", "70").replace("{height}", "92")
              div(
                style("margin", "10px"),
                style("padding", "10px"),
                style("border", "1px solid #ddd"),
                style("border-radius", "8px"),
                style("width", "100px"),
                style("background", "white")
              )(
                img(src := boxArtUrl, style("width", "70px"), style("height", "92px"), style("border-radius", "4px")),
                p(style("font-size", "0.7rem"), style("font-weight", "bold"), style("margin", "5px 0"), style("overflow", "hidden"), style("text-overflow", "ellipsis"), style("white-space", "nowrap"))(cat.name),
                button(onClick(Msg.UnfollowCategory(cat.id)), style("background", "#ff4646"), style("font-size", "0.7rem"), style("padding", "2px 5px"), style("width", "100%"))("Unfollow")
              )
            }
          )
      )).getOrElse(div())
    )

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None

  def main(args: Array[String]): Unit =
    launch("app")

case class Model(
    status: Option[String], 
    user: Option[TwitchUser], 
    twitchClientId: Option[String],
    searchQuery: String,
    searchResults: List[TwitchCategory],
    selectedCategoryIds: Set[String],
    followedCategories: List[TwitchCategory]
)

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
