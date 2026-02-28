package com.twitch.frontend

import tyrian.Html.*
import tyrian.*

object View:
  def view(model: Model): Html[Msg] =
    div(style("width", "100%"), style("display", "flex"), style("flex-direction", "column"), style("align-items", "center"))(
      h1("Twitch App"),
      div(style("margin-bottom", "20px"))(
        if (model.user.isEmpty)
          button(onClick(Msg.LoginWithTwitch), style("background", "#9146ff"))("Login with Twitch")
        else
          span()
      ),
      model.status.map(s => p(style("font-weight", "bold"), style("color", "#9146ff"))(s)).getOrElse(div()),
      
      model.user.map(u => div(style("width", "100%"), style("display", "flex"), style("flex-direction", "column"), style("align-items", "center"))(
        h2(s"Welcome, ${u.display_name}!"),
        img(src := u.profile_image_url, style("border-radius", "50%"), style("width", "80px"), style("margin-bottom", "10px")),
        button(onClick(Msg.Logout), style("background", "#ff4646"))("Logout"),
        
        hr(style("width", "100%"), style("margin", "20px 0")),
        
        h3("Search Categories"),
        div(style("display", "flex"), style("gap", "10px"), style("justify-content", "center"), style("margin-bottom", "20px"))(
          input(
            `type` := "text",
            placeholder := "Search for a category...",
            value := model.searchQuery,
            onInput(q => Msg.UpdateSearchQuery(q)),
            style("padding", "0.5rem"),
            style("border", "1px solid #ddd"),
            style("border-radius", "4px"),
            style("width", "250px")
          ),
          button(onClick(Msg.SearchCategories))("Search")
        ),
        
        div(style("display", "flex"), style("flex-wrap", "wrap"), style("justify-content", "center"), style("margin-top", "10px"))(
          model.searchResults.map { cat =>
            val isSelected = model.selectedCategoryIds.contains(cat.id)
            val isFollowed = model.followedCategories.exists(_.id == cat.id)
            val boxArtUrl = cat.box_art_url.replace("{width}", "140").replace("{height}", "185")
            
            div(
              style("margin", "10px"),
              style("padding", "10px"),
              style("border", if (isSelected) "2px solid #9146ff" else "1px solid #ddd"),
              style("border-radius", "8px"),
              style("width", "160px"),
              style("background", if (isSelected) "#f0e6ff" else "white"),
              style("display", "flex"),
              style("flex-direction", "column"),
              style("align-items", "center")
            )(
              div(
                onClick(Msg.ToggleCategorySelection(cat.id)),
                style("cursor", "pointer"),
                style("display", "flex"),
                style("flex-direction", "column"),
                style("align-items", "center")
              )(
                img(src := boxArtUrl, style("width", "140px"), style("height", "185px"), style("border-radius", "4px")),
                p(style("font-size", "0.9rem"), style("font-weight", "bold"), style("margin", "5px 0"), style("text-align", "center"))(cat.name)
              ),
              if (isFollowed)
                button(onClick(Msg.UnfollowCategory(cat.id)), style("background", "#ff4646"), style("margin-top", "5px"), style("padding", "5px 10px"))("Unfollow")
              else
                button(onClick(Msg.FollowCategory(cat)), style("background", "#9146ff"), style("margin-top", "5px"), style("padding", "5px 10px"))("Follow")
            )
          }
        ),
        
        hr(style("width", "100%"), style("margin", "20px 0")),
        h3("Your Followed Categories"),
        if (model.followedCategories.isEmpty)
          p("You haven't followed any categories yet.")
        else
          div(style("display", "flex"), style("flex-wrap", "wrap"), style("justify-content", "center"))(
            model.followedCategories.map { cat =>
              val boxArtUrl = cat.box_art_url.replace("{width}", "70").replace("{height}", "92")
              div(
                style("margin", "10px"),
                style("padding", "10px"),
                style("border", "1px solid #ddd"),
                style("border-radius", "8px"),
                style("width", "120px"),
                style("background", "white"),
                style("display", "flex"),
                style("flex-direction", "column"),
                style("align-items", "center")
              )(
                img(src := boxArtUrl, style("width", "70px"), style("height", "92px"), style("border-radius", "4px")),
                p(style("font-size", "0.7rem"), style("font-weight", "bold"), style("margin", "5px 0"), style("overflow", "hidden"), style("text-overflow", "ellipsis"), style("white-space", "nowrap"), style("width", "100%"), style("text-align", "center"))(cat.name),
                button(onClick(Msg.UnfollowCategory(cat.id)), style("background", "#ff4646"), style("font-size", "0.7rem"), style("padding", "2px 5px"))("Unfollow")
              )
            }
          )
      )).getOrElse(div())
    )
