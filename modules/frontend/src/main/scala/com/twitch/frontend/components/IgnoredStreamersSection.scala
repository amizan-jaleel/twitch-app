package com.twitch.frontend.components

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import fs2.concurrent.*
import fs2.dom.*
import com.twitch.frontend.{Model, ApiClient}
import com.twitch.core.*

object IgnoredStreamersSection:

  def ignoredStreamersPanel(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "w-full mb-6",
      div(
        cls := "bg-twitch-dark-card border border-gray-800 rounded-xl p-4 flex flex-col gap-4",
        p(cls := "text-sm text-gray-400 font-medium", "Ignored streamers (won't notify you):"),
        // Streamer pills
        div(
          cls := "flex flex-wrap gap-2",
          children <-- state.map { m =>
            m.ignoredStreamers.map { s =>
              streamerPill(state, s)
            }
          }
        ),
        // Empty state
        div(
          cls <-- state.map { m =>
            if m.ignoredStreamers.isEmpty then List("text-xs", "text-gray-500", "italic")
            else List("hidden")
          },
          "No ignored streamers yet. Use the ✕ button on live notifications to ignore a streamer."
        )
      )
    )

  private def streamerPill(
      state: SignallingRef[IO, Model],
      streamer: IgnoredStreamer
  ): Resource[IO, HtmlSpanElement[IO]] =
    span(
      cls := "bg-twitch-danger text-white text-xs px-2.5 py-1 rounded-full flex items-center gap-1.5",
      streamer.streamerName,
      button(
        cls := "hover:text-gray-300 text-white font-bold cursor-pointer text-xs leading-none",
        "✕",
        onClick --> { _.foreach(_ =>
          (ApiClient.removeIgnoredStreamer(streamer.streamerId) *>
            ApiClient.fetchIgnoredStreamers.flatMap(streamers =>
              state.update(_.copy(ignoredStreamers = streamers))
            )).start.void
        )}
      )
    )

  def ignoreButton(state: SignallingRef[IO, Model], notification: StreamNotification): Resource[IO, HtmlButtonElement[IO]] =
    button(
      cls := "text-gray-500 hover:text-twitch-danger text-xs transition-colors cursor-pointer",
      title := s"Ignore ${notification.streamerName}",
      "🚫",
      onClick --> { _.foreach(_ =>
        (ApiClient.addIgnoredStreamer(notification.streamerId, notification.streamerLogin, notification.streamerName) *>
          ApiClient.fetchIgnoredStreamers.flatMap(streamers =>
            state.update(_.copy(ignoredStreamers = streamers))
          )).start.void
      )}
    )
