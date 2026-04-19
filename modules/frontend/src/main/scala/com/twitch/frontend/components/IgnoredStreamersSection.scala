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
          "No ignored streamers yet. Search below or use the 🚫 button on notifications."
        ),
        // Search input
        p(cls := "text-sm text-gray-400 font-medium mt-2", "Search streamers to ignore:"),
        div(
          cls := "relative",
          div(
            cls := "flex gap-2",
            input.withSelf { self =>
              (
                typ := "text",
                placeholder := "Type a streamer name...",
                cls := "bg-twitch-dark border border-gray-700 text-white placeholder-gray-500 rounded-lg px-3 py-1.5 text-sm w-64 focus:outline-none focus:ring-2 focus:ring-twitch-purple focus:border-transparent transition-all",
                value <-- state.map(_.streamerSearchQuery),
                onInput --> { _.foreach(_ =>
                  self.value.get.flatMap { v =>
                    state.update(_.copy(streamerSearchQuery = v)) *>
                      (if v.trim.length >= 2 then
                        ApiClient.searchChannels(v.trim).flatMap(results =>
                          state.update(_.copy(streamerSearchResults = results))
                        ).start.void
                      else
                        state.update(_.copy(streamerSearchResults = Nil)))
                  }
                )}
              )
            },
            button(
              cls := "bg-gray-700 hover:bg-gray-600 text-gray-400 text-sm px-3 py-1.5 rounded-lg transition-colors cursor-pointer",
              "Clear",
              onClick --> { _.foreach(_ =>
                state.update(_.copy(streamerSearchQuery = "", streamerSearchResults = Nil))
              )}
            )
          ),
          // Search results dropdown
          div(
            cls <-- state.map { m =>
              if m.streamerSearchResults.isEmpty || m.streamerSearchQuery.trim.length < 2 then List("hidden")
              else List("absolute", "z-10", "mt-1", "w-full", "bg-twitch-dark", "border", "border-gray-700", "rounded-lg", "shadow-lg", "max-h-60", "overflow-y-auto")
            },
            children <-- state.map { m =>
              val ignoredIds = m.ignoredStreamers.map(_.streamerId).toSet
              m.streamerSearchResults.map { channel =>
                searchResultRow(state, channel, ignoredIds.contains(channel.id))
              }
            }
          )
        )
      )
    )

  private def searchResultRow(
      state: SignallingRef[IO, Model],
      channel: TwitchChannel,
      alreadyIgnored: Boolean
  ): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "flex items-center justify-between px-3 py-2 hover:bg-gray-800 transition-colors",
      div(
        cls := "flex items-center gap-2",
        img(
          src := channel.thumbnail_url,
          cls := "w-6 h-6 rounded-full",
          alt := channel.display_name
        ),
        span(cls := "text-white text-sm", channel.display_name),
        span(cls := "text-gray-500 text-xs", s"(${channel.broadcaster_login})"),
        if channel.is_live then
          span(cls := "text-twitch-live text-xs font-medium ml-1", "● LIVE")
        else
          span(cls := "text-gray-600 text-xs ml-1", "offline")
      ),
      if alreadyIgnored then
        span(cls := "text-gray-500 text-xs italic", "already ignored")
      else
        button(
          cls := "bg-twitch-danger hover:opacity-80 text-white text-xs px-2.5 py-1 rounded-lg transition-colors cursor-pointer",
          "Ignore",
          onClick --> { _.foreach(_ =>
            (ApiClient.addIgnoredStreamer(channel.id, channel.broadcaster_login, channel.display_name) *>
              ApiClient.fetchIgnoredStreamers.flatMap(streamers =>
                state.update(_.copy(ignoredStreamers = streamers, streamerSearchQuery = "", streamerSearchResults = Nil))
              )).start.void
          )}
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
