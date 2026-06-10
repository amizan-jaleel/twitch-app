package com.twitch.backend

case class ServerConfig(
  baseUrl: String,
  clientId: String,
  clientSecret: String,
  dbPassword: Option[String],
  dbUrl: String,
  dbUser: Option[String],
  dialect: SqlDialect,
  oauthStateSecret: String,
  port: Int,
  pushActionTokenSecret: String,
  redirectUri: String,
  sessionTokenEncryptionSecret: String,
  staticDir: String,
)

object ServerConfig {

  import cats.effect.IO

  private def requireEnv(name: String): IO[String] =
    IO.delay(sys.env.get(name)).flatMap {
      case Some(value) => IO.pure(value)
      case None => IO.raiseError(new RuntimeException(s"$name environment variable is not set"))
    }

  def fromEnv: IO[ServerConfig] =
    for {
      clientId <- requireEnv("TWITCH_CLIENT_ID")
      clientSecret <- requireEnv("TWITCH_CLIENT_SECRET")
      baseUrl <- IO.delay(sys.env.getOrElse("BASE_URL", "http://localhost:8080"))
      rawDbUrl <- IO.delay(
        sys
          .env
          .getOrElse(
            "DATABASE_URL",
            "jdbc:h2:./twitch_app_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
          ),
      )
      renderPattern = """^postgres(?:ql)?://([^:]+):([^@]+)@([^/]+)/(.+)$""".r
      (jdbcUrl, user, password) = rawDbUrl match {
        case renderPattern(u, p, host, db) =>
          val hostPort = if host.contains(":") then host else s"$host:5432"
          (s"jdbc:postgresql://$hostPort/$db", Some(u), Some(p))
        case _ => (rawDbUrl, None, None)
      }
      dialect = if jdbcUrl.startsWith("jdbc:postgresql") then SqlDialect.Postgres else SqlDialect.H2
      dbUser <- IO.delay(user.orElse(sys.env.get("DATABASE_USER")))
      dbPassword <- IO.delay(password.orElse(sys.env.get("DATABASE_PASS")))
      port <- IO.delay(sys.env.getOrElse("PORT", "8080").toInt)
      oauthStateSecret <- IO.delay(sys.env.getOrElse("OAUTH_STATE_SECRET", clientSecret))
      pushActionTokenSecret <- IO.delay(sys.env.getOrElse("PUSH_ACTION_TOKEN_SECRET", clientSecret))
      sessionTokenEncryptionSecret <- IO.delay(
        sys.env.getOrElse("SESSION_TOKEN_ENCRYPTION_KEY", clientSecret),
      )
      staticDir <- IO.delay(sys.env.getOrElse("STATIC_DIR", "./modules/frontend"))
    } yield ServerConfig(
      baseUrl = baseUrl,
      clientId = clientId,
      clientSecret = clientSecret,
      dbPassword = dbPassword,
      dbUrl = jdbcUrl,
      dbUser = dbUser,
      dialect = dialect,
      oauthStateSecret = oauthStateSecret,
      port = port,
      pushActionTokenSecret = pushActionTokenSecret,
      redirectUri = s"$baseUrl/auth/callback",
      sessionTokenEncryptionSecret = sessionTokenEncryptionSecret,
      staticDir = staticDir,
    )

}
