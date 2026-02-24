package com.example.frontend

import com.example.core.{TwitchUser, TwitchCategory}

case class Model(
    status: Option[String], 
    user: Option[TwitchUser], 
    twitchClientId: Option[String],
    searchQuery: String,
    searchResults: List[TwitchCategory],
    selectedCategoryIds: Set[String],
    followedCategories: List[TwitchCategory]
)

object Model:
  def init: Model =
    Model(
      status = None,
      user = None,
      twitchClientId = None,
      searchQuery = "",
      searchResults = Nil,
      selectedCategoryIds = Set.empty,
      followedCategories = Nil
    )
