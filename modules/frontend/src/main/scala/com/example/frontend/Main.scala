package com.example.frontend

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import tyrian.Html.*
import tyrian.*
import tyrian.http.*
import scala.scalajs.js.annotation.*
import com.example.core.{Ping, TwitchUser, AppConfig, TwitchCategory, TwitchSearchCategoriesResponse}
import io.circe.parser.decode

@JSExportTopLevel("TyrianApp")
object Main extends TyrianApp[IO, Msg, Model]:

  def router: Location => Msg =
    _ => Msg.NoOp

  val run: IO[Nothing] => Unit =
    _.unsafeRunAndForget()

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model("Checking session and fetching config...", None, None, None, "", Nil, Set.empty), 
     Cmd.Batch(
       Http.send(Request.get("/api/user"), Msg.fromUserResponse),
       Http.send(Request.get("/api/config"), Msg.fromConfigResponse)
     ))

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.SendPing =>
      (model.copy(status = Some("Sending ping...")), Http.send(Request.get("/api/ping"), Msg.fromHttpResponse))
    case Msg.GotPing(ping) =>
      (model.copy(status = Some(s"Server said: ${ping.message}")), Cmd.None)
    case Msg.PingError(err) =>
      (model.copy(status = Some(s"Error: $err")), Cmd.None)
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
    case Msg.NoOp =>
      (model, Cmd.None)

  def view(model: Model): Html[Msg] =
    div(
      h1("Twitch App"),
      p(model.message),
      div(
        button(onClick(Msg.SendPing))("Send Ping to Backend"),
        span(" "),
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
            val boxArtUrl = cat.box_art_url.replace("{width}", "140").replace("{height}", "185")
            
            div(
              onClick(Msg.ToggleCategorySelection(cat.id)),
              style("margin", "10px"),
              style("padding", "10px"),
              style("border", if (isSelected) "2px solid #9146ff" else "1px solid #ddd"),
              style("border-radius", "8px"),
              style("cursor", "pointer"),
              style("width", "160px"),
              style("background", if (isSelected) "#f0e6ff" else "white")
            )(
              img(src := boxArtUrl, style("width", "140px"), style("height", "185px"), style("border-radius", "4px")),
              p(style("font-size", "0.9rem"), style("font-weight", "bold"), style("margin", "5px 0"))(cat.name)
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
    message: String, 
    status: Option[String], 
    user: Option[TwitchUser], 
    twitchClientId: Option[String],
    searchQuery: String,
    searchResults: List[TwitchCategory],
    selectedCategoryIds: Set[String]
)

enum Msg:
  case SendPing
  case GotPing(ping: Ping)
  case GotUser(user: TwitchUser)
  case GotConfig(config: AppConfig)
  case PingError(error: String)
  case LoginWithTwitch
  case Logout
  case UpdateSearchQuery(query: String)
  case SearchCategories
  case GotSearchResults(results: List[TwitchCategory])
  case ToggleCategorySelection(id: String)
  case NoOp

object Msg:
  def fromHttpResponse: Decoder[Msg] = Decoder(
    response =>
      response.status match
        case Status(code, _) if code >= 200 && code < 300 =>
          decode[Ping](response.body) match
            case Right(ping) => Msg.GotPing(ping)
            case Left(err)   => Msg.PingError(s"JSON Decoding error: ${err.getMessage}")
        case Status(code, msg) =>
          Msg.PingError(s"Server returned $code: $msg"),
    error => Msg.PingError(s"Network error: ${error.toString}")
  )

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
            case Left(err)     => Msg.PingError(s"Config decoding error: ${err.getMessage}")
        case Status(code, msg) =>
          Msg.PingError(s"Server returned $code: $msg"),
    error => Msg.PingError(s"Config fetch network error: ${error.toString}")
  )

  def fromSearchResponse: Decoder[Msg] = Decoder(
    response =>
      response.status match
        case Status(code, _) if code >= 200 && code < 300 =>
          decode[TwitchSearchCategoriesResponse](response.body) match
            case Right(res) => Msg.GotSearchResults(res.data)
            case Left(err)  => Msg.PingError(s"Search decoding error: ${err.getMessage}")
        case Status(code, msg) =>
          Msg.PingError(s"Search failed with $code: $msg"),
    error => Msg.PingError(s"Search network error: ${error.toString}")
  )

  def fromLogoutResponse: Decoder[Msg] = Decoder(
    _ => Msg.NoOp,
    _ => Msg.NoOp
  )
