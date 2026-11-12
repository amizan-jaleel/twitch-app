package com.twitch.backend

import scala.concurrent.duration.*

import cats.effect.*
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.headers.Authorization

import com.twitch.core.*

class TwitchApiClientSpec extends CatsEffectSuite {

  private val game = TwitchCategory("1", "Game 1", "https://example.com/1")

  private def tokenResponse(token: String): Response[IO] =
    Response[IO](Status.Ok).withEntity(TwitchTokenResponse(token, 3600, None, None, "bearer"))

  private def gamesResponse: Response[IO] =
    Response[IO](Status.Ok).withEntity(TwitchSearchCategoriesResponse(List(game), None))

  private def makeApi(app: HttpApp[IO]): IO[TwitchApiClient] =
    TwitchApiClient.make(
      client = Client.fromHttpApp(app),
      clientId = "client-id",
      clientSecret = "client-secret",
    )

  // Stands in for one poller's authenticated request through the shared cache.
  private def poll(api: TwitchApiClient, path: String): IO[List[TwitchCategory]] =
    api.withTokenRefresh {
      api
        .client
        .expect[TwitchSearchCategoriesResponse](
          api.buildAuthedRequest(Uri.unsafeFromString(s"https://api.twitch.tv$path"), None),
        )
        .map(_.data)
    }

  test("concurrent pollers share an in-flight app token request") {
    for {
      tokenCalls <- Ref.of[IO, Int](0)
      tokenStarted <- Deferred[IO, Unit]
      releaseToken <- Deferred[IO, Unit]
      secondStarted <- Deferred[IO, Unit]
      app = HttpApp[IO] { request =>
        request.uri.path.renderString match {
          case "/oauth2/token" =>
            tokenCalls.update(_ + 1) *> tokenStarted.complete(()).void *>
              releaseToken.get.as(tokenResponse("app-token"))
          case _ => IO.pure(gamesResponse)
        }
      }
      api <- makeApi(app)
      first <- poll(api, "/helix/streams").start
      _ <- tokenStarted.get
      second <- (secondStarted.complete(()).void *> poll(api, "/helix/games/top")).start
      _ <- secondStarted.get
      _ <- IO.sleep(100.millis)
      _ <- releaseToken.complete(())
      _ <- (first.joinWithNever, second.joinWithNever).parTupled.timeout(5.seconds)
      calls <- tokenCalls.get
    } yield assertEquals(calls, 1)
  }

  test("a delayed failure for an old token does not discard a refreshed token") {
    for {
      tokenCalls <- Ref.of[IO, Int](0)
      expireToken <- Ref.of[IO, Boolean](false)
      oldRequests <- Ref.of[IO, Int](0)
      bothOldRequestsStarted <- Deferred[IO, Unit]
      refreshedTokenUsed <- Deferred[IO, Unit]
      app = HttpApp[IO] { request =>
        request.uri.path.renderString match {
          case "/oauth2/token" =>
            tokenCalls.updateAndGet(_ + 1).map { count =>
              tokenResponse(if count == 1 then "old-token" else "new-token")
            }
          case path =>
            val usesOldToken = request
              .headers
              .get[Authorization]
              .contains(Authorization(Credentials.Token(AuthScheme.Bearer, "old-token")))
            expireToken.get.flatMap { expired =>
              if !expired then IO.pure(gamesResponse)
              else if !usesOldToken then refreshedTokenUsed.complete(()).void.as(gamesResponse)
              else
                // Both pollers fail with the old token. The streams poller fails
                // first and refreshes; the games poller's failure arrives only
                // after the refreshed token is already in use.
                oldRequests.updateAndGet(_ + 1).flatMap { count =>
                  IO.whenA(count == 2)(bothOldRequestsStarted.complete(()).void)
                } *> bothOldRequestsStarted.get *>
                  IO.whenA(path != "/helix/streams")(refreshedTokenUsed.get) *>
                  IO.pure(Response[IO](Status.Unauthorized))
            }
        }
      }
      api <- makeApi(app)
      _ <- poll(api, "/helix/games/top")
      _ <- expireToken.set(true)
      result <- (poll(api, "/helix/streams"), poll(api, "/helix/games/top"))
        .parTupled
        .timeout(5.seconds)
      calls <- tokenCalls.get
    } yield {
      assertEquals(result, (List(game), List(game)))
      assertEquals(calls, 2)
    }
  }

}
