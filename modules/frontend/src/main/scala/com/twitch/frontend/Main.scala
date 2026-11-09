package com.twitch.frontend

import scala.scalajs.js

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.concurrent.*
import fs2.dom.*
import org.scalajs.dom

import com.twitch.core.*
import com.twitch.frontend.components.*

object Main extends IOWebApp:

  def render: Resource[IO, HtmlDivElement[IO]] =
    for
      state <- SignallingRef[IO].of(Model()).toResource
      _ <- (
        ApiClient.fetchUser.flatMap(u => state.update(_.copy(user = u))),
        ApiClient
          .fetchConfig
          .flatMap(c => state.update(s => s.copy(twitchClientId = c.map(_.twitchClientId)))),
        ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats))),
        ApiClient
          .fetchTagFilters
          .flatMap(filters =>
            state.update(m => m.copy(tagFilters = m.tagFilters.copy(filters = filters))),
          ),
        ApiClient
          .fetchIgnoredStreamers
          .flatMap(streamers =>
            state.update(m =>
              m.copy(ignoredStreamers = m.ignoredStreamers.copy(streamers = streamers)),
            ),
          ),
        ApiClient.fetchTopGameIds.flatMap(ids => state.update(_.copy(topGameIds = ids))),
      ).parTupled.toResource
      _ <- startNotificationStream(state).background
      _ <- initPushNotifications(state).background
      _ <- syncIgnoredFromBackground(state).toResource
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
        val title = s"${n.streamerName} is live playing ${n.categoryName}!"
        // Fire through the service worker's showNotification so the notification can carry
        // an "Ignore streamer" action button — the plain Notification constructor ignores
        // `actions`. The SW's notificationclick handler performs the ignore / open-stream.
        // Buttons render where the platform supports them (e.g. Android Chrome); macOS
        // Chrome shows the notification but not the buttons. Browsers without a service
        // worker fall back to a basic (button-less) notification.
        val sw = dom.window.navigator.asInstanceOf[js.Dynamic].serviceWorker
        if js.isUndefined(sw) || sw == null then fallbackBrowserNotification(n, title)
        else
          var shown = false
          val showFallback: () => Unit = () =>
            if !shown then
              shown = true
              fallbackBrowserNotification(n, title)

          val options = js.Dynamic.literal(
            body = s"${n.categoryName}: ${n.streamTitle}",
            icon = n.thumbnailUrl,
            tag = n.streamerId,
            data = js.Dynamic.literal(
              streamerId = n.streamerId,
              streamerLogin = n.streamerLogin,
              streamerName = n.streamerName,
            ),
            actions = js.Array(js.Dynamic.literal(action = "ignore", title = "Ignore streamer")),
          )
          val fallbackTimeout =
            dom.window.setTimeout(() => showFallback(), 2_000)
          val _ = sw
            .ready
            .asInstanceOf[js.Promise[js.Dynamic]]
            .`then`[js.Any](
              ((reg: js.Dynamic) =>
                if !shown then
                  shown = true
                  dom.window.clearTimeout(fallbackTimeout)
                  reg.showNotification(title, options)
                else ()
              ): js.Function1[js.Dynamic, js.Any],
            )
    }

  // Fallback for browsers without a service worker: a basic notification. The Notification
  // constructor doesn't support action buttons, so there's no "Ignore streamer" here.
  private def fallbackBrowserNotification(n: StreamNotification, title: String): Unit =
    val notification = new dom.Notification(
      title,
      new dom.NotificationOptions {
        body = s"${n.categoryName}: ${n.streamTitle}"
        icon = n.thumbnailUrl
      },
    )
    notification.onclick = (_: dom.Event) => {
      val _ = dom.window.open(s"https://twitch.tv/${n.streamerLogin}", "_blank")
      notification.close()
    }

  private def startNotificationStream(state: SignallingRef[IO, Model]): IO[Unit] =
    state
      .discrete
      .filter(_.user.isDefined)
      .take(1)
      .evalMap { _ =>
        requestNotificationPermission *>
          ApiClient.streamNotifications { n =>
            state.get.flatMap { m =>
              if m.notifications.notifications.exists(_.streamerId == n.streamerId) then IO.unit
              else
                state.update(m =>
                  m.copy(notifications =
                    m.notifications
                      .copy(notifications =
                        (n :: m.notifications.notifications).take(Defaults.NotificationHistoryLimit),
                      ),
                  ),
                ) *>
                  fireBrowserNotification(n)
            }
          }
      }
      .compile
      .drain

  /** Initialize Capacitor push notifications on native platforms. Waits for a logged-in user, then
    * requests permission, registers for FCM, and sends the device token to the backend. On web
    * (non-native), this is a no-op — SSE handles it.
    */
  private def initPushNotifications(state: SignallingRef[IO, Model]): IO[Unit] =
    if !CapacitorPush.isNative then IO.unit
    else
      // Wait until we have a logged-in user
      state
        .discrete
        .filter(_.user.isDefined)
        .take(1)
        .evalMap { _ =>
          // Request notification permission, then register for FCM and send the token to
          // the backend. Notification display and taps are handled natively (iOS
          // AppDelegate, Android service) — handleApplicationNotifications is disabled, so
          // there are no JS notification listeners here.
          for
            perm <- IO.fromPromise(IO(CapacitorPush.requestPermissions()))
            _ <- IO.whenA(perm.receive == "granted") {
              IO.async_[String] { cb =>
                val _ = CapacitorPush.onRegistration(token => cb(Right(token.value)))
                val _ = CapacitorPush.onRegistrationError { err =>
                  cb(Left(new RuntimeException(s"Push registration failed: ${err.error}")))
                }
                val _ = CapacitorPush.register()
              }.flatMap { token =>
                IO.println(
                  s"FCM token obtained, registering with backend (platform: ${CapacitorPush.platform})",
                ) *>
                  ApiClient.registerPushToken(token, CapacitorPush.platform)
              }
            }
          yield ()
        }
        .compile
        .drain

  /** Keep the in-app ignore list in sync when a streamer is ignored from a notification
    * action — that runs in the service worker, outside the page. Refresh whenever the tab
    * becomes visible again (the common case: the user returns to the tab after ignoring),
    * and also on a direct service-worker message (page already open/visible).
    */
  private def syncIgnoredFromBackground(state: SignallingRef[IO, Model]): IO[Unit] =
    IO {
      val refresh: () => Unit = () =>
        ApiClient
          .fetchIgnoredStreamers
          .flatMap(streamers =>
            state.update(m =>
              m.copy(ignoredStreamers = m.ignoredStreamers.copy(streamers = streamers)),
            ),
          )
          .unsafeRunAndForget()

      // Tab became visible again (e.g. switching back after tapping "Ignore streamer").
      val _ = dom.document.addEventListener(
        "visibilitychange",
        { (_: dom.Event) =>
          if dom.document.asInstanceOf[js.Dynamic].visibilityState.asInstanceOf[String] == "visible"
          then refresh()
        }: js.Function1[dom.Event, Unit],
      )

      // Service worker reported an ignore while a page is open/visible.
      val sw = dom.window.navigator.asInstanceOf[js.Dynamic].serviceWorker
      if !js.isUndefined(sw) && sw != null then
        val _ = sw.addEventListener(
          "message",
          { (event: js.Dynamic) =>
            val data = event.data
            val isIgnored =
              !js.isUndefined(data) && data != null &&
                data.selectDynamic("type").asInstanceOf[String] == "streamer-ignored"
            if isIgnored then refresh()
          }: js.Function1[js.Dynamic, Unit],
        )
    }

  private def appView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "min-h-screen bg-twitch-dark flex flex-col items-center",
      // Popular game warning popup overlay
      SearchSection.popularGameWarning(state),
      // Header bar
      div(
        cls := "w-full bg-twitch-dark-card border-b border-gray-800 px-6 py-4 flex items-center justify-center",
        h1(cls := "text-2xl font-bold text-white tracking-tight", "Twitch Category Tracker"),
      ),
      // Main content
      div(
        cls := "w-full max-w-5xl mx-auto px-4 py-8 flex flex-col items-center gap-6",
        LoginSection.welcomePage(state),
        LoginSection.statusBar(state),
        loggedInView(state),
      ),
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
          state.map(m => m.user.map(u => s"Welcome, ${u.display_name}!").getOrElse("")),
        ),
        img(
          src <-- state.map(_.user.map(_.profile_image_url).getOrElse("")),
          cls := "rounded-full w-20 h-20 ring-2 ring-twitch-purple",
        ),
        button(
          cls := "bg-twitch-danger hover:bg-red-600 text-white font-medium px-4 py-2 rounded-lg transition-colors cursor-pointer",
          "Logout",
          onClick --> {
            _.foreach(_ => ApiClient.postLogout *> IO(org.scalajs.dom.window.location.reload()))
          },
        ),
      ),
      // Getting started instructions
      div(
        cls := "w-full bg-twitch-dark-card border border-gray-700 rounded-xl p-6 max-w-xl mx-auto",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Getting Started"),
        div(
          cls := "flex flex-col gap-3",
          instructionStep(
            "1",
            "Allow notifications",
            if CapacitorPush.isNative then
              "Allow notifications so you know when streams go live."
            else
              "Make sure you give this site permission to send you browser notifications so you know when streams go live.",
          ),
          instructionStep(
            "2",
            "Follow categories",
            "Search for Twitch categories below and follow the ones you want to track.",
          ),
          if CapacitorPush.isNative then
            instructionStep(
              "3",
              "That\u2019s it!",
              "You\u2019ll get notified when your favorite categories go live.",
            )
          else
            instructionStep(
              "3",
              "Keep this tab open",
              "You\u2019ll get notified when your favorite categories go live! Pin this tab so you don\u2019t close it by accident.",
            ),
        ),
      ),
      // Search section
      div(
        cls := "w-full border-t border-gray-800 pt-8",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Search Categories"),
        SearchSection.searchInput(state),
        SearchSection.searchResultsView(state),
        SearchSection.paginationView(state),
      ),
      // Live Now section
      div(
        cls := "w-full border-t border-gray-800 pt-8",
        h3(
          cls := "text-lg font-bold text-white mb-4 text-center",
          "Live Now in Your Followed Categories",
        ),
        TagFiltersSection.tagFiltersPanel(state),
        IgnoredStreamersSection.ignoredStreamersPanel(state),
        NotificationsSection.notificationsView(state),
      ),
      // Followed Categories section
      div(
        cls := "w-full border-t border-gray-800 pt-8",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Your Followed Categories"),
        FollowedSection.followedCategoriesView(state),
      ),
    )

  private def instructionStep(
    num: String,
    title: String,
    description: String,
  ): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "flex items-start gap-4",
      div(
        cls := "flex-shrink-0 w-8 h-8 bg-twitch-purple rounded-full flex items-center justify-center text-white font-bold text-sm",
        num,
      ),
      div(
        cls := "flex flex-col gap-0.5",
        p(cls := "text-white font-semibold text-sm", title),
        p(cls := "text-gray-400 text-sm", description),
      ),
    )
