package com.twitch.frontend

import com.twitch.core.*

case class Model(
    status: Option[String] = None,
    user: Option[TwitchUser] = None,
    twitchClientId: Option[String] = None,
    searchQuery: String = "",
    searchResults: Vector[TwitchCategory] = Vector.empty,
    selectedCategoryIds: Set[String] = Set.empty,
    followedCategories: List[TwitchCategory] = Nil,
    paginationCursor: Option[String] = None,
    currentPage: Int = 0,
    pageSize: Int = Defaults.SearchPageSize,
    notifications: List[StreamNotification] = Nil,
    tagFilters: List[TagFilter] = Nil,
    newIncludeTag: String = "",
    newExcludeTag: String = "",
    ignoredStreamers: List[IgnoredStreamer] = Nil
)
