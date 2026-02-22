package com.example.backend

import cats.effect.*
import com.comcast.ip4s.*
import org.http4s.ember.server.*
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, StaticFile}
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.server.middleware.CORS
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.client.dsl.io.*
//import org.http4s.Method.*
import org.http4s.Uri
import org.http4s.UrlForm
import org.http4s.headers.{Authorization, Location}
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Header
import org.typelevel.ci.*
import org.http4s.server.staticcontent.*
import com.example.core.{Ping, TwitchTokenResponse, TwitchUser, TwitchUsersResponse, AppConfig}

object Main extends IOApp.Simple:

  // These should be configured via environment variables
  private val clientId = sys.env.getOrElse("TWITCH_CLIENT_ID", "your_client_id")
  private val clientSecret = sys.env.getOrElse("TWITCH_CLIENT_SECRET", "your_client_secret")
  private val redirectUri = "http://localhost:8080/auth/callback"

  private def authRoutes(client: Client[IO], userSession: Ref[IO, Option[TwitchUser]]) = HttpRoutes.of[IO] {
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
        _ <- userSession.set(Some(user))
        res <- Found(Location(uri"/"))
      } yield res

      flow.handleErrorWith { err =>
        IO.println(s"Auth flow failed: ${err.getMessage}") *>
          InternalServerError(s"Auth flow failed. Check server logs. Error: ${err.getMessage}")
      }
  }

  object CodeQueryParamMatcher extends QueryParamDecoderMatcher[String]("code")

  private def apiRoutes(userSession: Ref[IO, Option[TwitchUser]]) = HttpRoutes.of[IO] {
    case GET -> Root / "config" =>
      Ok(AppConfig(clientId))
    case GET -> Root / "ping" =>
      IO.println("Received ping request, responding with pong") *> Ok(Ping("pong"))
    case GET -> Root / "user" =>
      userSession.get.flatMap {
        case Some(user) => Ok(user)
        case None       => NotFound("No user logged in")
      }
    case POST -> Root / "logout" =>
      userSession.set(None) *> Ok("Logged out")
  }

  private def helloWorldService = HttpRoutes.of[IO] {
    case GET -> Root / "hello" / name =>
      Ok(s"Hello, $name.")
  }

  def run: IO[Unit] =
    for {
      userSession <- IO.ref[Option[TwitchUser]](None)
      _ <- EmberClientBuilder.default[IO].build.use { client =>
        val host = host"0.0.0.0"
        val port = port"8080"
        
        val frontendService = fileService[IO](FileService.Config("./modules/frontend"))
        
        val httpApp = Router(
          "/api" -> apiRoutes(userSession),
          "/" -> authRoutes(client, userSession),
          "/" -> HttpRoutes.of[IO] {
            case req @ GET -> Root =>
              StaticFile.fromPath(fs2.io.file.Path("./modules/frontend/index.html"), Some(req)).getOrElseF(NotFound())
          },
          "/" -> frontendService,
          "/" -> helloWorldService
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
