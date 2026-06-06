package com.twitch.frontend

import scala.concurrent.duration.*

import cats.effect.*
import cats.effect.std.{Dispatcher, Queue}
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.{MediaType, Method, Request as Http4sRequest, Uri}
import org.http4s.dom.FetchClientBuilder
import org.http4s.headers.`Content-Type`
import org.scalajs.dom
import org.scalajs.dom.RequestCredentials

import com.twitch.core.*

object ApiClient:

  private val httpClient = FetchClientBuilder[IO]
    .withCredentials(RequestCredentials.`same-origin`)
    .create

  def fetchUser: IO[Option[TwitchUser]] =
    fetchUserOnce.handleErrorWith { _ =>
      IO.sleep(2.seconds) *> fetchUserOnce.handleErrorWith { _ =>
        IO.sleep(3.seconds) *> fetchUserOnce
      }
    }

  private def fetchUserOnce: IO[Option[TwitchUser]] =
    httpClient.run(Http4sRequest[IO](uri = Uri.unsafeFromString("/api/user"))).use { resp =>
      if (resp.status.isSuccess)
        resp.as[String].map(body => decode[TwitchUser](body).toOption)
      else
        IO.pure(None)
    }

  def fetchConfig: IO[Option[AppConfig]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/config")).attempt.map {
      case Right(body) => decode[AppConfig](body).toOption
      case Left(_) => None
    }

  def fetchFollowed: IO[List[TwitchCategory]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/followed")).attempt.map {
      case Right(body) => decode[FollowedCategoriesResponse](body).map(_.categories).getOrElse(Nil)
      case Left(_) => Nil
    }

  def searchCategories(
    query: String,
    after: Option[String] = None,
  ): IO[Option[TwitchSearchCategoriesResponse]] =
    val baseUri = Uri.unsafeFromString("/api/search/categories").withQueryParam("query", query)
    val uri = after.fold(baseUri)(c => baseUri.withQueryParam("after", c))
    httpClient.expect[String](uri).attempt.map {
      case Right(body) => decode[TwitchSearchCategoriesResponse](body).toOption
      case Left(_) => None
    }

  def fetchTopGameIds: IO[Set[String]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/top-game-ids")).attempt.map {
      case Right(body) => decode[TopGameIdsResponse](body).map(_.gameIds).getOrElse(Set.empty)
      case Left(_) => Set.empty
    }

  def postFollow(cat: TwitchCategory): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/follow"))
      .withEntity(FollowRequest(cat).asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
    httpClient.expect[String](req).void

  def postUnfollow(id: String): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString(s"/api/unfollow/$id"))
    httpClient.expect[String](req).void

  def postLogout: IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/logout"))
    httpClient.expect[String](req).void.handleError(_ => ())

  def fetchTagFilters: IO[List[TagFilter]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/tag-filters")).attempt.map {
      case Right(body) => decode[TagFiltersResponse](body).map(_.filters).getOrElse(Nil)
      case Left(_) => Nil
    }

  def addTagFilter(filterType: String, tag: String): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/tag-filters/add"))
      .withEntity(AddTagFilterRequest(filterType, tag).asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
    httpClient.expect[String](req).void

  def removeTagFilter(filterType: String, tag: String): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/tag-filters/remove"))
      .withEntity(AddTagFilterRequest(filterType, tag).asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
    httpClient.expect[String](req).void

  def searchChannels(query: String): IO[List[TwitchChannel]] =
    val uri = Uri.unsafeFromString("/api/search/channels").withQueryParam("query", query)
    httpClient.expect[String](uri).attempt.map {
      case Right(body) => decode[TwitchSearchChannelsResponse](body).map(_.data).getOrElse(Nil)
      case Left(_) => Nil
    }

  def fetchIgnoredStreamers: IO[List[IgnoredStreamer]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/ignored-streamers")).attempt.map {
      case Right(body) => decode[IgnoredStreamersResponse](body).map(_.streamers).getOrElse(Nil)
      case Left(_) => Nil
    }

  def addIgnoredStreamer(
    streamerId: String,
    streamerLogin: String,
    streamerName: String,
  ): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/ignored-streamers/add"))
      .withEntity(
        AddIgnoredStreamerRequest(streamerId, streamerLogin, streamerName).asJson.noSpaces,
      )
      .withContentType(`Content-Type`(MediaType.application.json))
    httpClient.expect[String](req).void

  def removeIgnoredStreamer(streamerId: String): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/ignored-streamers/remove"))
      .withEntity(RemoveIgnoredStreamerRequest(streamerId).asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
    httpClient.expect[String](req).void

  def registerPushToken(token: String, platform: String): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/push/register"))
      .withEntity(PushRegisterRequest(token, platform).asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
    httpClient.expect[String](req).void.handleError(_ => ())

  def unregisterPushToken(token: String): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/push/unregister"))
      .withEntity(PushUnregisterRequest(token).asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
    httpClient.expect[String](req).void.handleError(_ => ())

  def streamNotifications(onNotification: StreamNotification => IO[Unit]): IO[Nothing] =
    sseStream
      .evalMap(onNotification)
      .handleErrorWith { e =>
        Stream.eval(
          IO.println(s"SSE connection error: $e, reconnecting in ${Defaults.SseReconnectDelay}...")
            *> IO.sleep(Defaults.SseReconnectDelay),
        ) >> sseStream.evalMap(onNotification)
      }
      .compile
      .drain
      .flatMap(_ => IO.never)

  private def sseStream: Stream[IO, StreamNotification] =
    Stream.resource(sseResource).flatMap(nextEvent => Stream.repeatEval(nextEvent))

  private def sseResource: Resource[IO, IO[StreamNotification]] =
    for
      dispatcher <- Dispatcher.sequential[IO]
      queue <- Resource.eval(Queue.unbounded[IO, Either[Throwable, StreamNotification]])
      _ <- Resource.make(IO {
        val es = new dom.EventSource("/api/notifications/stream")
        es.addEventListener(
          "stream-live",
          (e: dom.MessageEvent) =>
            decode[StreamNotification](e.data.asInstanceOf[String]).foreach { n =>
              dispatcher.unsafeRunAndForget(queue.offer(Right(n)))
            },
        )
        es.onerror = (_: dom.Event) =>
          dispatcher.unsafeRunAndForget(
            queue.offer(Left(new RuntimeException("SSE connection error"))),
          )
        es
      })(es => IO(es.close()))
    yield queue.take.flatMap(IO.fromEither)
