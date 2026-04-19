package com.twitch.frontend

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import cats.syntax.all.*
import fs2.concurrent.*
import fs2.dom.*
import org.scalajs.dom
import scala.scalajs.js
import com.twitch.core.*
import com.twitch.frontend.components.*

object Main extends IOWebApp:

  def render: Resource[IO, HtmlDivElement[IO]] =
    for
      state <- SignallingRef[IO].of(Model()).toResource
      _ <- (
        ApiClient.fetchUser.flatMap(u => state.update(_.copy(user = u))),
        ApiClient.fetchConfig.flatMap(c => state.update(s => s.copy(twitchClientId = c.map(_.twitchClientId)))),
        ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats))),
        ApiClient.fetchTagFilters.flatMap(filters => state.update(_.copy(tagFilters = filters))),
        ApiClient.fetchIgnoredStreamers.flatMap(streamers => state.update(_.copy(ignoredStreamers = streamers)))
      ).parTupled.toResource
      _ <- startNotificationStream(state).background
      _ <- initPushNotifications(state).background
      app <- appView(state)
    yield app

  private def requestNotificationPermission: IO[Unit] =
    IO {
      if dom.Notification.permission == "default" then
        dom.Notification.requestPermission((_: String) => ())
    }

  private def fireBrowserNotification(n: StreamNotification): IO[Unit] =
    IO {
      if dom.Notification.permission == "granted" then
        val notification = new dom.Notification(
          s"${n.streamerName} is live playing ${n.categoryName}!",
          new dom.NotificationOptions {
            body = s"${n.categoryName}: ${n.streamTitle}"
            icon = n.thumbnailUrl
          }
        )
        notification.onclick = (_: dom.Event) => {
          val _ = dom.window.open(s"https://twitch.tv/${n.streamerLogin}", "_blank")
          notification.close()
        }
    }

  private def startNotificationStream(state: SignallingRef[IO, Model]): IO[Unit] =
    state.discrete
      .filter(_.user.isDefined)
      .take(1)
      .evalMap { _ =>
        requestNotificationPermission *>
          ApiClient.streamNotifications { n =>
            state.get.flatMap { m =>
              if m.notifications.exists(_.streamerId == n.streamerId) then IO.unit
              else
                state.update(m => m.copy(notifications = (n :: m.notifications).take(Defaults.NotificationHistoryLimit))) *>
                  fireBrowserNotification(n)
            }
          }
      }
      .compile.drain

  /** Initialize Capacitor push notifications on native platforms.
    * Waits for a logged-in user, then requests permission, registers
    * for FCM, and sends the device token to the backend.
    * On web (non-native), this is a no-op — SSE handles it.
    */
  private def initPushNotifications(state: SignallingRef[IO, Model]): IO[Unit] =
    if !CapacitorPush.isNative then IO.unit
    else
      // Wait until we have a logged-in user
      state.discrete
        .filter(_.user.isDefined)
        .take(1)
        .evalMap { _ =>
          val setup = for
            // Request notification permission from the OS
            perm <- IO.fromPromise(IO(CapacitorPush.requestPermissions()))
            _ <- IO.whenA(perm.receive == "granted") {
              // Set up the registration callback before calling register()
              IO.async_[String] { cb =>
                val _ = CapacitorPush.onRegistration { token =>
                  cb(Right(token.value))
                }
                val _ = CapacitorPush.onRegistrationError { err =>
                  cb(Left(new RuntimeException(s"Push registration failed: ${err.error}")))
                }
                val _ = CapacitorPush.register()
              }.flatMap { token =>
                IO.println(s"FCM token obtained, registering with backend (platform: ${CapacitorPush.platform})") *>
                  ApiClient.registerPushToken(token, CapacitorPush.platform)
              }
            }
          yield ()

          // Set up handler for notifications received while app is in foreground
          val foregroundHandler = IO {
            CapacitorPush.onPushNotificationReceived { notification =>
              // Foreground notifications are already shown by SSE stream,
              // but log for debugging
              dom.console.log(s"Push received in foreground: ${notification.title}")
            }
          }

          // Set up handler for when user taps a notification
          val tapHandler = IO {
            CapacitorPush.onPushNotificationActionPerformed { action =>
              val data = action.notification.data
              if !js.isUndefined(data) then
                val dynData = data.asInstanceOf[js.Dynamic]
                val login = dynData.selectDynamic("streamerLogin")
                if !js.isUndefined(login) then
                  val _ = dom.window.open(s"https://twitch.tv/${login.asInstanceOf[String]}", "_blank")
            }
          }

          setup *> foregroundHandler *> tapHandler
        }
        .compile.drain

  private def appView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "min-h-screen bg-twitch-dark flex flex-col items-center",
      // Header bar
      div(
        cls := "w-full bg-twitch-dark-card border-b border-gray-800 px-6 py-4 flex items-center justify-center",
        h1(cls := "text-2xl font-bold text-white tracking-tight", "Twitch Category Tracker")
      ),
      // Main content
      div(
        cls := "w-full max-w-5xl mx-auto px-4 py-8 flex flex-col items-center gap-6",
        LoginSection.welcomePage(state),
        LoginSection.statusBar(state),
        loggedInView(state)
      )
    )

  private def loggedInView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls <-- state.map { m =>
        if m.user.isDefined then List("w-full", "flex", "flex-col", "items-center", "gap-8")
        else List("hidden")
      },
      // User profile
      div(
        cls := "flex flex-col items-center gap-3",
        h2(
          cls := "text-xl font-semibold text-white",
          state.map(m => m.user.map(u => s"Welcome, ${u.display_name}!").getOrElse(""))
        ),
        img(
          src <-- state.map(_.user.map(_.profile_image_url).getOrElse("")),
          cls := "rounded-full w-20 h-20 ring-2 ring-twitch-purple"
        ),
        button(
          cls := "bg-twitch-danger hover:bg-red-600 text-white font-medium px-4 py-2 rounded-lg transition-colors cursor-pointer",
          "Logout",
          onClick --> { _.foreach { _ =>
            ApiClient.postLogout *> IO(org.scalajs.dom.window.location.reload())
          }}
        )
      ),
      // Getting started instructions
      div(
        cls := "w-full bg-twitch-dark-card border border-gray-700 rounded-xl p-6 max-w-xl mx-auto",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Getting Started"),
        div(
          cls := "flex flex-col gap-3",
          instructionStep("1", "Allow notifications", "Make sure you give this site permission to send you browser notifications so you know when streams go live."),
          instructionStep("2", "Follow categories", "Search for Twitch categories below and follow the ones you want to track."),
          instructionStep("3", "Keep this tab open", "You\u2019ll get notified when your favorite categories go live! Pin this tab so you don\u2019t close it by accident.")
        )
      ),
      // Search section
      div(
        cls := "w-full border-t border-gray-800 pt-8",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Search Categories"),
        SearchSection.searchInput(state),
        SearchSection.searchResultsView(state),
        SearchSection.paginationView(state)
      ),
      // Live Now section
      div(
        cls := "w-full border-t border-gray-800 pt-8",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Live Now in Your Followed Categories"),
        TagFiltersSection.tagFiltersPanel(state),
        IgnoredStreamersSection.ignoredStreamersPanel(state),
        NotificationsSection.notificationsView(state)
      ),
      // Followed Categories section
      div(
        cls := "w-full border-t border-gray-800 pt-8",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Your Followed Categories"),
        FollowedSection.followedCategoriesView(state)
      )
    )

  private def instructionStep(num: String, title: String, description: String): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "flex items-start gap-4",
      div(
        cls := "flex-shrink-0 w-8 h-8 bg-twitch-purple rounded-full flex items-center justify-center text-white font-bold text-sm",
        num
      ),
      div(
        cls := "flex flex-col gap-0.5",
        p(cls := "text-white font-semibold text-sm", title),
        p(cls := "text-gray-400 text-sm", description)
      )
    )
