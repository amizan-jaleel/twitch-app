package com.twitch.backend

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.*
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.server.staticcontent.*
import doobie.h2.H2Transactor
import doobie.hikari.HikariTransactor
import com.zaxxer.hikari.HikariConfig
import cats.effect.std.Queue
import com.twitch.core.StreamNotification

object TwitchServer extends IOApp.Simple:

  def run: IO[Unit] =
    for
      clientId <- IO.blocking(sys.env.getOrElse("TWITCH_CLIENT_ID", {
        System.err.println("ERROR: TWITCH_CLIENT_ID environment variable is not set")
        sys.exit(1)
      }))
      clientSecret <- IO.blocking(sys.env.getOrElse("TWITCH_CLIENT_SECRET", {
        System.err.println("ERROR: TWITCH_CLIENT_SECRET environment variable is not set")
        sys.exit(1)
      }))
      baseUrl <- IO.blocking(sys.env.getOrElse("BASE_URL", "http://localhost:8080"))
      redirectUri = s"$baseUrl/auth/callback"
      settings <- IO.blocking(AppSettings.load)
      _ <- start(clientId, clientSecret, baseUrl, redirectUri, settings)
    yield ()

  private def start(clientId: String, clientSecret: String, baseUrl: String, redirectUri: String, settings: AppSettings): IO[Unit] =
    val rawDbUrl = sys.env.getOrElse("DATABASE_URL",
      "jdbc:h2:./twitch_app_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")

    // Parse postgres://user:pass@host[:port]/db into JDBC URL + separate credentials
    case class ParsedDb(jdbcUrl: String, user: Option[String], password: Option[String])
    val parsed: ParsedDb =
      val renderPattern = """^postgres(?:ql)?://([^:]+):([^@]+)@([^/]+)/(.+)$""".r
      rawDbUrl match
        case renderPattern(user, pass, host, db) =>
          val hostPort = if host.contains(":") then host else s"$host:5432"
          ParsedDb(s"jdbc:postgresql://$hostPort/$db", Some(user), Some(pass))
        case _ => ParsedDb(rawDbUrl, None, None)

    val dbUrl = parsed.jdbcUrl
    val isPostgres = dbUrl.startsWith("jdbc:postgresql")
    val dialect = if isPostgres then SqlDialect.Postgres else SqlDialect.H2

    val transactorResource: Resource[IO, doobie.Transactor[IO]] =
      if isPostgres then
        val hikariConfig = new HikariConfig()
        hikariConfig.setDriverClassName("org.postgresql.Driver")
        hikariConfig.setJdbcUrl(dbUrl)
        parsed.user.orElse(sys.env.get("DATABASE_USER")).foreach(hikariConfig.setUsername)
        parsed.password.orElse(sys.env.get("DATABASE_PASS")).foreach(hikariConfig.setPassword)
        HikariTransactor.fromHikariConfig[IO](hikariConfig)
      else
        for {
          ec <- Resource.eval(IO.executionContext)
          xa <- H2Transactor.newH2Transactor[IO](dbUrl, "sa", "", ec)
        } yield xa

    transactorResource.use { xa =>
      val db = new Database(xa, dialect)
      for {
        _ <- db.initDb
        pendingOAuthStates <- IO.ref(Set.empty[String])
        notificationQueues <- IO.ref(Map.empty[String, (String, Queue[IO, StreamNotification])])
        _ <- EmberClientBuilder.default[IO].build.use { client =>
          val host = host"0.0.0.0"
          val port = Port.fromInt(sys.env.getOrElse("PORT", "8080").toInt).getOrElse(port"8080")

          val staticDir = sys.env.getOrElse("STATIC_DIR", "./modules/frontend")

          val emailService = sys.env.get("SENDGRID_API_KEY").map(key =>
            new EmailService(client, key, settings.emailFrom, settings.emailFromName)
          )

          val pushServiceIO: IO[Option[PushNotificationService]] =
            val keyIO = sys.env.get("FCM_SERVICE_ACCOUNT_JSON") match
              case Some(json) => ServiceAccountKey.fromJson(json).map(Some(_))
              case None => sys.env.get("FCM_SERVICE_ACCOUNT_KEY") match
                case Some(keyPath) => ServiceAccountKey.fromFile(keyPath).map(Some(_))
                case None => IO.none
            keyIO.flatMap {
              case Some(key) =>
                IO.ref(Option.empty[(String, java.time.Instant)]).flatMap { tokenRef =>
                  IO.println("Push notifications enabled").as(
                    Some(new PushNotificationService(client, key.projectId, key, settings.pushParallelSends, db, tokenRef))
                  )
                }
              case None =>
                IO.println("Push notifications disabled (set FCM_SERVICE_ACCOUNT_JSON or FCM_SERVICE_ACCOUNT_KEY)").as(None)
            }.handleErrorWith { err =>
              IO.println(s"Warning: Failed to load FCM service account key: ${err.getMessage}").as(None)
            }

          val frontendService = fileService[IO](FileService.Config(staticDir))

          for
            pushService <- pushServiceIO
            routes = new Routes(clientId, clientSecret, redirectUri, client, pendingOAuthStates, db, notificationQueues, settings, emailService)
            httpApp = Router(
              "/api" -> routes.apiRoutes,
              "/" -> routes.authRoutes,
              "/" -> HttpRoutes.of[IO] {
                case req @ GET -> Root =>
                  StaticFile.fromPath(fs2.io.file.Path(s"$staticDir/index.html"), Some(req)).getOrElseF(NotFound())
              },
              "/" -> frontendService
            ).orNotFound
            corsApp = CORS.policy.withAllowOriginAll(httpApp)
            poller <- StreamPoller.make(clientId, clientSecret, client, db, notificationQueues, settings, pushService)
            _ <- (
              poller.start.void,
              IO.println(s"Server started at $baseUrl") *>
                EmberServerBuilder
                  .default[IO]
                  .withHost(host)
                  .withPort(port)
                  .withHttpApp(corsApp)
                  .build
                  .useForever
            ).parTupled
          yield ()
        }
      } yield ()
    }
