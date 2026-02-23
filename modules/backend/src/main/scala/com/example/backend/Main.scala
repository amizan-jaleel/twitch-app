package com.example.backend

import cats.effect.*
import com.comcast.ip4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.*
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.{HttpRoutes, Request, ResponseCookie, StaticFile}

import java.util.UUID
import com.example.core.*
import org.http4s.{AuthScheme, Credentials, Header, Uri, UrlForm}
import org.http4s.headers.{Authorization, Location}
import org.http4s.server.staticcontent.*
import org.typelevel.ci.*
import doobie.*
import doobie.implicits.*
import doobie.h2.*

case class SessionData(
    user: TwitchUser,
    accessToken: String
)

object Main extends IOApp.Simple:

  // These should be configured via environment variables
  private val clientId = sys.env.getOrElse("TWITCH_CLIENT_ID", {
    System.err.println("ERROR: TWITCH_CLIENT_ID environment variable is not set")
    sys.exit(1)
  })
  private val clientSecret = sys.env.getOrElse("TWITCH_CLIENT_SECRET", {
    System.err.println("ERROR: TWITCH_CLIENT_SECRET environment variable is not set")
    sys.exit(1)
  })
  private val redirectUri = "http://localhost:8080/auth/callback"

  private def getSession(req: Request[IO], userSession: Ref[IO, Map[String, SessionData]]): IO[Option[SessionData]] = {
    val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
    sessionId.fold(IO.pure(None: Option[SessionData]))(id => userSession.get.map(_.get(id)))
  }

  private def initDb(xa: Transactor[IO]): IO[Unit] = {
    val sql = sql"""
      CREATE TABLE IF NOT EXISTS followed_categories (
        user_id VARCHAR NOT NULL,
        category_id VARCHAR NOT NULL,
        name VARCHAR NOT NULL,
        box_art_url VARCHAR NOT NULL,
        PRIMARY KEY (user_id, category_id)
      )
    """.update.run
    sql.transact(xa).void
  }

  private def authRoutes(client: Client[IO], userSession: Ref[IO, Map[String, SessionData]]) = HttpRoutes.of[IO] {
    case GET -> Root / "auth" / "callback" :? CodeQueryParamMatcher(code) =>
      val flow = for {
        _ <- IO.println(s"Received auth code: $code")
        _ <- if (clientId == "your_client_id" || clientSecret == "your_client_secret") {
          IO.println("WARNING: Using placeholder Twitch credentials! Set TWITCH_CLIENT_ID and TWITCH_CLIENT_SECRET env vars in your Run Configuration.")
        } else IO.unit
        
        req = POST(
          UrlForm(
            "client_id" -> clientId,
            "client_secret" -> clientSecret,
            "code" -> code,
            "grant_type" -> "authorization_code",
            "redirect_uri" -> redirectUri
          ),
          uri"https://id.twitch.tv/oauth2/token"
        )
        
        tokenResponse <- client.run(req).use { resp =>
          if (resp.status.isSuccess) {
            resp.as[TwitchTokenResponse]
          } else {
            // Get raw body text regardless of content type or decoding errors
            resp.bodyText.compile.string.flatMap { errorBody =>
              IO.raiseError(new RuntimeException(s"unexpected HTTP status: ${resp.status} for request POST https://id.twitch.tv/oauth2/token. Response body: $errorBody"))
            }
          }
        }
        
        _ <- IO.println(s"Received token: ${tokenResponse.access_token}")
        userResponse <- client.expect[TwitchUsersResponse](
          GET(
            uri"https://api.twitch.tv/helix/users",
            Authorization(Credentials.Token(AuthScheme.Bearer, tokenResponse.access_token)),
            Header.Raw(ci"Client-Id", clientId)
          )
        )
        user = userResponse.data.head
        _ <- IO.println(s"Found user: ${user.display_name}")
        sessionId = UUID.randomUUID().toString
        _ <- userSession.update(_ + (sessionId -> SessionData(user, tokenResponse.access_token)))
        res <- Found(Location(uri"/")).map(_.addCookie(ResponseCookie("session_id", sessionId, path = Some("/"), httpOnly = true)))
      } yield res

      flow.handleErrorWith { err =>
        IO.println(s"Auth flow failed: ${err.getMessage}") *>
          InternalServerError(s"Auth flow failed. Check server logs. Error: ${err.getMessage}")
      }
  }

  private object CodeQueryParamMatcher extends QueryParamDecoderMatcher[String]("code")
  private object SearchQueryParamMatcher extends QueryParamDecoderMatcher[String]("query")

  private def apiRoutes(client: Client[IO], userSession: Ref[IO, Map[String, SessionData]], xa: Transactor[IO]) = HttpRoutes.of[IO] {
    case GET -> Root / "config" =>
      Ok(AppConfig(clientId))
    case req @ GET -> Root / "user" =>
      getSession(req, userSession).flatMap {
        case Some(data) => Ok(data.user)
        case None       => NotFound("Not logged in")
      }
    case req @ GET -> Root / "followed" =>
      getSession(req, userSession).flatMap {
        case Some(data) =>
          sql"SELECT category_id, name, box_art_url FROM followed_categories WHERE user_id = ${data.user.id}"
            .query[TwitchCategory]
            .to[List]
            .transact(xa)
            .flatMap(cats => Ok(FollowedCategoriesResponse(cats)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "follow" =>
      req.as[FollowRequest].flatMap { followReq =>
        getSession(req, userSession).flatMap {
          case Some(data) =>
            sql"""
              MERGE INTO followed_categories (user_id, category_id, name, box_art_url)
              KEY(user_id, category_id)
              VALUES (${data.user.id}, ${followReq.category.id}, ${followReq.category.name}, ${followReq.category.box_art_url})
            """.update.run.transact(xa) *> Ok("Followed")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "unfollow" / categoryId =>
      getSession(req, userSession).flatMap {
        case Some(data) =>
          sql"DELETE FROM followed_categories WHERE user_id = ${data.user.id} AND category_id = $categoryId"
            .update.run.transact(xa) *> Ok("Unfollowed")
        case None => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "search" / "categories" :? SearchQueryParamMatcher(query) =>
      getSession(req, userSession).flatMap {
        case Some(data) =>
          val uri = uri"https://api.twitch.tv/helix/search/categories".withQueryParam("query", query)
          client.expect[TwitchSearchCategoriesResponse](
            GET(
              uri,
              Authorization(Credentials.Token(AuthScheme.Bearer, data.accessToken)),
              Header.Raw(ci"Client-Id", clientId)
            )
          ).flatMap(Ok(_))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "logout" =>
      val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
      for {
        _ <- sessionId.fold(IO.unit)(id => userSession.update(_ - id))
        res <- Ok("Logged out").map(_.removeCookie("session_id"))
      } yield res
  }

  def run: IO[Unit] =
    val dbUrl = "jdbc:h2:./twitch_app_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
    val transactorResource = for {
      ec <- Resource.eval(IO.executionContext)
      xa <- H2Transactor.newH2Transactor[IO](dbUrl, "sa", "", ec)
    } yield xa

    transactorResource.use { xa =>
      for {
        _ <- initDb(xa)
        userSession <- IO.ref[Map[String, SessionData]](Map.empty)
        _ <- EmberClientBuilder.default[IO].build.use { client =>
          val host = host"0.0.0.0"
          val port = port"8080"

          val frontendService = fileService[IO](FileService.Config("./modules/frontend"))

          val httpApp = Router(
            "/api" -> apiRoutes(client, userSession, xa),
            "/" -> authRoutes(client, userSession),
            "/" -> HttpRoutes.of[IO] {
              case req @ GET -> Root =>
                StaticFile.fromPath(fs2.io.file.Path("./modules/frontend/index.html"), Some(req)).getOrElseF(NotFound())
            },
            "/" -> frontendService
          ).orNotFound

          val corsApp = CORS.policy.withAllowOriginAll(httpApp)

          IO.println(s"Server started at http://localhost:$port") *>
            EmberServerBuilder
              .default[IO]
              .withHost(host)
              .withPort(port)
              .withHttpApp(corsApp)
              .build
              .useForever
        }
      } yield ()
    }
