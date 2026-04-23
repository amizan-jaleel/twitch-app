package com.twitch.frontend.components

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import com.twitch.core.*
import com.twitch.frontend.Model
import fs2.concurrent.*
import fs2.dom.*

object NotificationsSection:

  def notificationsView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "w-full",
      div(
        cls := "grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4",
        children <-- state.map { m =>
          val followedIds = m.followedCategories.map(_.id).toSet
          val ignoredIds = m.ignoredStreamers.map(_.streamerId).toSet
          val relevant = m.notifications.filter(n => followedIds.contains(n.categoryId) && !ignoredIds.contains(n.streamerId))
          if relevant.isEmpty then
            List(div(
              cls := "col-span-full text-center py-8",
              p(cls := "text-gray-500", "No streams detected live yet. Notifications will appear here.")
            ))
          else
            relevant.map(n => notificationCard(state, n))
        }
      )
    )

  private def notificationCard(state: SignallingRef[IO, Model], n: StreamNotification): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "bg-twitch-dark-card rounded-xl border border-gray-800 overflow-hidden hover:border-twitch-live transition-all duration-200 hover:shadow-lg group",
      // Clickable area: thumbnail + stream info (opens streamer page)
      div(
        cls := "cursor-pointer",
        onClick --> { _.foreach(_ =>
          IO { val _ = org.scalajs.dom.window.open(s"https://twitch.tv/${n.streamerLogin}", "_blank") }
        )},
        // Thumbnail with LIVE badge
        div(
          cls := "relative",
          img(
            src := n.thumbnailUrl,
            cls := "w-full h-28 object-cover"
          ),
          span(
            cls := "absolute top-2 left-2 bg-twitch-live text-white text-xs font-bold px-2 py-0.5 rounded animate-live-pulse",
            "LIVE"
          )
        ),
        // Card content (clickable part)
        div(
          cls := "p-3 pb-1 flex flex-col gap-1",
          p(
            cls := "text-xs font-bold text-twitch-purple",
            n.categoryName
          ),
          p(
            cls := "text-sm font-bold text-white",
            n.streamerName
          ),
          p(
            cls := "text-xs text-gray-400 truncate",
            n.streamTitle
          ),
          p(
            cls := "text-xs text-gray-500",
            s"${n.viewerCount} viewers"
          ),
          div(
            cls := "flex flex-wrap gap-1 mt-1",
            n.tags.take(com.twitch.frontend.Defaults.TagsPerNotificationCard).map { tag =>
              span(
                cls := "text-[10px] bg-twitch-dark-hover text-gray-400 px-1.5 py-0.5 rounded",
                tag
              )
            }
          )
        )
      ),
      // Ignore button (NOT inside the clickable area)
      div(
        cls := "px-3 pb-3 flex justify-end",
        IgnoredStreamersSection.ignoreButton(state, n)
      )
    )
