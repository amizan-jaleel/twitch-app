package com.twitch.backend

import munit.FunSuite

import com.twitch.backend.db.PushSubscriptionRow
import com.twitch.core.StreamNotification

class PushNotificationServiceSpec extends FunSuite {

  private val notification = StreamNotification(
    categoryId = "cat1",
    categoryName = "Just Chatting",
    streamerId = "streamer1",
    streamerLogin = "streamer_login",
    streamerName = "Streamer",
    streamTitle = "Live now",
    thumbnailUrl = "https://img.test/thumb.jpg",
    viewerCount = 42,
  )

  private def subscription(platform: String): PushSubscriptionRow =
    PushSubscriptionRow(
      id = "sub1",
      userId = "user1",
      deviceToken = "fcm-token-123",
      platform = platform,
      createdAt = 0L,
    )

  test("messagePayload keeps Android payload data-only") {
    val message = PushNotificationService
      .messagePayload(subscription("android"), notification)
      .hcursor
      .downField("message")

    assertEquals(message.downField("token").as[String], Right("fcm-token-123"))
    assertEquals(
      message.downField("data").downField("streamerLogin").as[String],
      Right("streamer_login"),
    )
    assertEquals(
      message.downField("data").downField("streamerName").as[String],
      Right("Streamer"),
    )
    assert(message.downField("notification").focus.isEmpty)
    assert(message.downField("apns").focus.isEmpty)
  }

  test("messagePayload adds visible APNs alert payload for iOS") {
    val message = PushNotificationService
      .messagePayload(subscription("ios"), notification)
      .hcursor
      .downField("message")

    assertEquals(
      message.downField("notification").downField("title").as[String],
      Right("Streamer is live!"),
    )
    assertEquals(
      message.downField("notification").downField("body").as[String],
      Right("Playing Just Chatting: Live now"),
    )
    assertEquals(
      message.downField("apns").downField("headers").downField("apns-push-type").as[String],
      Right("alert"),
    )
    assertEquals(
      message.downField("apns").downField("headers").downField("apns-priority").as[String],
      Right("10"),
    )
    assertEquals(
      message
        .downField("apns")
        .downField("payload")
        .downField("aps")
        .downField("alert")
        .downField("body")
        .as[String],
      Right("Playing Just Chatting: Live now"),
    )
    assertEquals(
      message
        .downField("apns")
        .downField("payload")
        .downField("aps")
        .downField("category")
        .as[String],
      Right("STREAM_LIVE"),
    )
    assertEquals(
      message.downField("data").downField("streamerLogin").as[String],
      Right("streamer_login"),
    )
  }

  test("messagePayload treats iOS platform case-insensitively") {
    val message = PushNotificationService
      .messagePayload(subscription("IOS"), notification)
      .hcursor
      .downField("message")

    assertEquals(
      message.downField("apns").downField("headers").downField("apns-push-type").as[String],
      Right("alert"),
    )
    assertEquals(
      message.downField("notification").downField("title").as[String],
      Right("Streamer is live!"),
    )
  }

}
