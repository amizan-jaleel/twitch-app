package com.twitch.backend

import cats.effect.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.headers.{`Content-Type`, Authorization}
import io.circe.*
import io.circe.syntax.*

class EmailService(
  apiKey: String,
  client: Client[IO],
  fromEmail: String,
  fromName: String,
) extends EmailNotifier {

  def sendWelcomeEmail(toEmail: String, displayName: String): IO[Unit] = {
    val body = Json.obj(
      "personalizations" -> Json.arr(
        Json.obj(
          "to" -> Json.arr(Json.obj("email" -> toEmail.asJson)),
        ),
      ),
      "from" -> Json.obj(
        "email" -> fromEmail.asJson,
        "name" -> fromName.asJson,
      ),
      "subject" -> s"Welcome to Twitch Category Tracker, $displayName!".asJson,
      "content" -> Json.arr(
        Json.obj(
          "type" -> "text/html".asJson,
          "value" -> welcomeHtml(displayName).asJson,
        ),
      ),
    )

    val req = Request[IO](
      method = Method.POST,
      uri = uri"https://api.sendgrid.com/v3/mail/send",
    ).withEntity(body.noSpaces)
      .putHeaders(
        Authorization(Credentials.Token(AuthScheme.Bearer, apiKey)),
        `Content-Type`(MediaType.application.json),
      )

    client.run(req).use { resp =>
      if resp.status.isSuccess then IO.unit
      else
        resp.bodyText.compile.string.flatMap { errorBody =>
          IO.raiseError(
            new RuntimeException(
              s"SendGrid API error: ${resp.status} - $errorBody",
            ),
          )
        }
    }
  }

  private def escapeHtml(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#x27;")

  private def welcomeHtml(displayName: String): String = {
    val safeDisplayName = escapeHtml(displayName)
    s"""<html><body style="font-family: sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; color: #333;">
       |<h1 style="color: #9146FF;">Welcome to Twitch Category Tracker, $safeDisplayName!</h1>
       |<p>Thanks for signing up! Here's how to get the most out of the app:</p>
       |<ol style="line-height: 1.8;">
       |  <li><strong>Allow notifications</strong> &mdash; make sure you give the site permission to send browser notifications.</li>
       |  <li><strong>Search & follow categories</strong> &mdash; find your favorite Twitch categories and follow them.</li>
       |  <li><strong>Keep the tab open</strong> &mdash; you'll get notified when streams go live! Pin the tab so you don't close it by accident.</li>
       |</ol>
       |<hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;">
       |<p style="font-size: 14px; color: #666;">Have feedback or ideas? Reach out anytime:</p>
       |<ul style="font-size: 14px; line-height: 1.8; color: #666;">
       |  <li><a href="https://discord.gg/pk2McPvsb9" style="color: #9146FF;">Join our Discord server</a></li>
       |  <li><a href="https://twitch.tv/aabil11" style="color: #9146FF;">Catch me on Twitch</a></li>
       |  <li><a href="mailto:aabil11@twitchnotify.com" style="color: #9146FF;">Email me</a></li>
       |</ul>
       |<p>Happy watching!</p>
       |</body></html>""".stripMargin
  }

}
