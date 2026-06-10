package com.twitch.backend

import cats.effect.*
import cats.effect.std.Queue
import doobie.Transactor
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.server.Router
import org.http4s.server.staticcontent.*

import com.twitch.backend.auth.SessionTokenCipher
import com.twitch.core.StreamNotification

case class App(
  corsApp: HttpApp[IO],
  poller: StreamPoller,
  topGamesPoller: TopGamesPoller,
)

object AppWiring {

  def build(
    config: ServerConfig,
    settings: AppSettings,
    xa: Transactor[IO],
    client: Client[IO],
  ): IO[App] = {
    val followRepo = new db.FollowRepository(xa, config.dialect)
    val tagFilterRepo = new db.TagFilterRepository(xa, config.dialect)
    val ignoredStreamerRepo = new db.IgnoredStreamerRepository(xa, config.dialect)
    val userRepo = new db.UserRepository(xa)
    val sessionTokenCipher = SessionTokenCipher.fromSecret(config.sessionTokenEncryptionSecret)
    val sessionRepo = new db.SessionRepository(xa, sessionTokenCipher)
    val pushRepo = new db.PushSubscriptionRepository(xa, config.dialect)
    val topGamesRepo = new db.TopGamesRepository(xa)

    val emailService = sys
      .env
      .get("SENDGRID_API_KEY")
      .map(key =>
        new EmailService(
          apiKey = key,
          client = client,
          fromEmail = settings.emailFrom,
          fromName = settings.emailFromName,
        ),
      )

    val pushActionTokens =
      new PushActionTokenService(config.pushActionTokenSecret, settings.pushActionTokenTtl)
    val oauthStateTokens =
      new OAuthStateTokenService(config.oauthStateSecret, settings.oauthStateTtl)

    val pushServiceIO: IO[Option[PushNotificationService]] = {
      val keyIO = sys.env.get("FCM_SERVICE_ACCOUNT_JSON") match {
        case Some(json) => ServiceAccountKey.fromJson(json).map(Some(_))
        case None =>
          sys.env.get("FCM_SERVICE_ACCOUNT_KEY") match {
            case Some(keyPath) => ServiceAccountKey.fromFile(keyPath).map(Some(_))
            case None => IO.none
          }
      }
      keyIO
        .flatMap {
          case Some(key) =>
            for {
              tokenCache <- IO.ref(Option.empty[(String, java.time.Instant)])
              tokenMutex <- cats.effect.std.Mutex[IO]
              _ <- IO.println("Push notifications enabled")
            } yield Some(
              new PushNotificationService(
                client = client,
                parallelSends = settings.pushParallelSends,
                projectId = key.projectId,
                pushActionTokens = pushActionTokens,
                pushRepo = pushRepo,
                serviceAccountKey = key,
                tokenCache = tokenCache,
                tokenMutex = tokenMutex,
              ),
            )
          case None =>
            IO.println(
              "Push notifications disabled (set FCM_SERVICE_ACCOUNT_JSON or FCM_SERVICE_ACCOUNT_KEY)",
            ).as(None)
        }
        .handleErrorWith { err =>
          IO.println(s"Warning: Failed to load FCM service account key: ${err.getMessage}").as(None)
        }
    }

    for {
      _ <- db.Schema.initDb(xa, config.dialect)
      _ <- sessionRepo.encryptPlaintextTokens
      notificationQueues <- IO.ref(Map.empty[String, (String, Queue[IO, StreamNotification])])
      pushService <- pushServiceIO
      twitchApi = new TwitchApiClient(
        client = client,
        clientId = config.clientId,
        clientSecret = config.clientSecret,
      )
      sessionManager = new auth.SessionManager(
        sessionRepo,
        twitchApi,
        settings.tokenRefreshSkew,
        settings.sessionTtl,
      )
      authRoutes = new routes.AuthRoutes(
        clientId = config.clientId,
        emailService = emailService,
        oauthStateTokens = oauthStateTokens,
        redirectUri = config.redirectUri,
        sessionRepo = sessionRepo,
        sessionTtl = settings.sessionTtl,
        twitchApi = twitchApi,
        userRepo = userRepo,
      )
      apiRoutes = new routes.ApiRoutes(
        clientId = config.clientId,
        followRepo = followRepo,
        ignoredStreamerRepo = ignoredStreamerRepo,
        notificationQueues = notificationQueues,
        pushActionTokens = pushActionTokens,
        pushRepo = pushRepo,
        sessionManager = sessionManager,
        sessionRepo = sessionRepo,
        settings = settings,
        tagFilterRepo = tagFilterRepo,
        topGamesRepo = topGamesRepo,
        twitchApi = twitchApi,
      )
      frontendService = fileService[IO](FileService.Config(config.staticDir))
      httpApp = Router(
        "/api" -> apiRoutes.routes,
        "/" -> authRoutes.routes,
        "/" -> HttpRoutes.of[IO] {
          case req @ GET -> Root =>
            StaticFile
              .fromPath(fs2.io.file.Path(s"${config.staticDir}/index.html"), Some(req))
              .getOrElseF(NotFound())
        },
        "/" -> frontendService,
      ).orNotFound
      securedApp = SecurityHeaders(httpApp, hsts = config.baseUrl.startsWith("https://"))
      poller <- StreamPoller.make(
        client = client,
        clientId = config.clientId,
        clientSecret = config.clientSecret,
        followRepo = followRepo,
        ignoredStreamerRepo = ignoredStreamerRepo,
        notificationQueues = notificationQueues,
        pushRepo = pushRepo,
        pushService = pushService,
        settings = settings,
        tagFilterRepo = tagFilterRepo,
      )
      topGamesPoller <- TopGamesPoller.make(
        client = client,
        clientId = config.clientId,
        clientSecret = config.clientSecret,
        settings = settings,
        topGamesRepo = topGamesRepo,
      )
    } yield App(securedApp, poller, topGamesPoller)
  }

}
