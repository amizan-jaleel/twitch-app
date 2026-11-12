package com.twitch.backend

import scala.concurrent.duration.*

import cats.effect.*
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.headers.Authorization

import com.twitch.core.*

class TwitchApiClientSpec extends CatsEffectSuite {

  private val game1 = TwitchCategory("1", "Game 1", "https://example.com/1")
  private val game2 = TwitchCategory("2", "Game 2", "https://example.com/2")
  private val game3 = TwitchCategory("3", "Game 3", "https://example.com/3")

  private def stream(id: String, gameId: String): TwitchStream =
    TwitchStream(
      id = id,
      user_id = s"user$id",
      user_login = s"user$id",
      user_name = s"User $id",
      game_id = gameId,
      game_name = s"Game $gameId",
      `type` = "live",
      title = "Live",
      viewer_count = 1,
      started_at = "2026-01-01T00:00:00Z",
      thumbnail_url = "https://example.com/thumbnail",
    )

  private def tokenResponse(token: String): Response[IO] =
    Response[IO](Status.Ok).withEntity(TwitchTokenResponse(token, 3600, None, None, "bearer"))

  private def gamesResponse: Response[IO] =
    Response[IO](Status.Ok).withEntity(TwitchSearchCategoriesResponse(List(game1), None))

  private def streamsResponse: Response[IO] =
    Response[IO](Status.Ok).withEntity(TwitchStreamsResponse(List(stream("1", "1")), None))

  private def makeApi(app: HttpApp[IO]): IO[TwitchApiClient] =
    TwitchApiClient.make(
      client = Client.fromHttpApp(app),
      clientId = "client-id",
      clientSecret = "client-secret",
      streamsPageSize = 37,
      parallelCategories = 2,
    )

  test("polling operations send authenticated requests, paginate, and reuse the app token") {
    for {
      requests <- Ref.of[IO, List[Request[IO]]](Nil)
      app = HttpApp[IO] { request =>
        requests.update(_ :+ request) *> {
          val params = request.uri.query.params
          request.uri.path.renderString match {
            case "/oauth2/token" => IO.pure(tokenResponse("app-token"))
            case "/helix/streams" =>
              val response = (params.get("game_id"), params.get("after")) match {
                case (Some("1"), None) =>
                  TwitchStreamsResponse(
                    List(stream("1", "1")),
                    Some(TwitchPagination(Some("next"))),
                  )
                case (Some("1"), Some("next")) =>
                  TwitchStreamsResponse(List(stream("2", "1")), None)
                case (Some("2"), None) => TwitchStreamsResponse(List(stream("3", "2")), None)
                case _ => TwitchStreamsResponse(Nil, None)
              }
              IO.pure(Response[IO](Status.Ok).withEntity(response))
            case "/helix/games/top" =>
              val response = params.get("after") match {
                case None =>
                  TwitchSearchCategoriesResponse(List(game1), Some(TwitchPagination(Some("next"))))
                case Some("next") =>
                  TwitchSearchCategoriesResponse(
                    List(game2, game3),
                    Some(TwitchPagination(Some("unused"))),
                  )
                case _ => TwitchSearchCategoriesResponse(Nil, None)
              }
              IO.pure(Response[IO](Status.Ok).withEntity(response))
            case _ => IO.pure(Response[IO](Status.NotFound))
          }
        }
      }
      api <- makeApi(app)
      streams <- api.liveStreams(List("1", "2"))
      games <- api.topGames(2)
      allRequests <- requests.get
    } yield {
      assertEquals(streams.map(_.id), List("1", "2", "3"))
      assertEquals(games, List(game1, game2))
      assertEquals(allRequests.count(_.uri.path.renderString == "/oauth2/token"), 1)
      val apiRequests = allRequests.filterNot(_.uri.path.renderString == "/oauth2/token")
      assertEquals(apiRequests.size, 5)
      assert(
        apiRequests.forall(
          _.headers
            .get[Authorization]
            .contains(
              Authorization(Credentials.Token(AuthScheme.Bearer, "app-token")),
            ),
        ),
      )
      assert(
        apiRequests.forall(
          _.headers
            .headers
            .exists(h => h.name.toString.equalsIgnoreCase("Client-Id") && h.value == "client-id"),
        ),
      )
      val streamRequests = apiRequests.filter(_.uri.path.renderString == "/helix/streams")
      assertEquals(streamRequests.size, 3)
      assert(streamRequests.forall(_.uri.query.params.get("first").contains("37")))
      val gameRequests = apiRequests.filter(_.uri.path.renderString == "/helix/games/top")
      assertEquals(gameRequests.size, 2)
      assert(gameRequests.forall(_.uri.query.params.get("first").contains("100")))
      assert(gameRequests.exists(_.uri.query.params.get("after").contains("next")))
    }
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
          case "/helix/streams" => IO.pure(streamsResponse)
          case "/helix/games/top" => IO.pure(gamesResponse)
          case _ => IO.pure(Response[IO](Status.NotFound))
        }
      }
      api <- makeApi(app)
      first <- api.liveStreams(List("1")).start
      _ <- tokenStarted.get
      second <- (secondStarted.complete(()).void *> api.topGames(1)).start
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
          case "/helix/streams" | "/helix/games/top" =>
            val isStreams = request.uri.path.renderString == "/helix/streams"
            val success = if isStreams then streamsResponse else gamesResponse
            val usesOldToken = request
              .headers
              .get[Authorization]
              .contains(
                Authorization(Credentials.Token(AuthScheme.Bearer, "old-token")),
              )
            expireToken.get.flatMap { expired =>
              if !expired then IO.pure(success)
              else if !usesOldToken then refreshedTokenUsed.complete(()).void.as(success)
              else
                oldRequests.updateAndGet(_ + 1).flatMap { count =>
                  IO.whenA(count == 2)(bothOldRequestsStarted.complete(()).void)
                } *> bothOldRequestsStarted.get *>
                  (if isStreams then IO.unit else refreshedTokenUsed.get) *>
                  IO.pure(Response[IO](Status.Unauthorized))
            }
          case _ => IO.pure(Response[IO](Status.NotFound))
        }
      }
      api <- makeApi(app)
      _ <- api.topGames(1)
      _ <- expireToken.set(true)
      result <- (api.liveStreams(List("1")), api.topGames(1)).parTupled.timeout(5.seconds)
      calls <- tokenCalls.get
    } yield {
      assertEquals(result._1.map(_.id), List("1"))
      assertEquals(result._2, List(game1))
      assertEquals(calls, 2)
    }
  }

}
