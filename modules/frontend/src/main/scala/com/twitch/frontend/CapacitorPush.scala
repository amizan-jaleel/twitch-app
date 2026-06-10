package com.twitch.frontend

import org.scalajs.dom
import scala.scalajs.js

object CapacitorPush:

  private def capacitor: js.Dynamic = js.Dynamic.global.Capacitor
  private def plugin: js.Dynamic = capacitor.Plugins.PushNotifications

  private def capacitorExists: Boolean =
    !js.isUndefined(dom.window.asInstanceOf[js.Dynamic].Capacitor)

  def isNative: Boolean =
    capacitorExists && capacitor.isNativePlatform().asInstanceOf[Boolean]

  def platform: String =
    if isNative then capacitor.getPlatform().asInstanceOf[String] else "web"

  // ── Result types ──────────────────────────────────────────────────

  @js.native
  trait PermissionStatus extends js.Object:
    val receive: String = js.native

  @js.native
  trait PushToken extends js.Object:
    val value: String = js.native

  @js.native
  trait RegistrationError extends js.Object:
    val error: String = js.native

  @js.native
  trait PushNotificationSchema extends js.Object:
    val title: String = js.native
    val body: String = js.native
    val data: js.UndefOr[js.Object] = js.native

  @js.native
  trait ActionPerformed extends js.Object:
    val notification: PushNotificationSchema = js.native

  // ── Public Scala API ──────────────────────────────────────────────

  def requestPermissions(): js.Promise[PermissionStatus] =
    plugin.requestPermissions().asInstanceOf[js.Promise[PermissionStatus]]

  def register(): js.Promise[Unit] =
    plugin.register().asInstanceOf[js.Promise[Unit]]

  def onRegistration(cb: PushToken => Unit): js.Promise[js.Any] =
    plugin
      .addListener(
        "registration",
        { (data: js.Any) => cb(data.asInstanceOf[PushToken]) }: js.Function1[js.Any, Unit],
      )
      .asInstanceOf[js.Promise[js.Any]]

  def onRegistrationError(cb: RegistrationError => Unit): js.Promise[js.Any] =
    plugin
      .addListener(
        "registrationError",
        { (data: js.Any) => cb(data.asInstanceOf[RegistrationError]) }: js.Function1[js.Any, Unit],
      )
      .asInstanceOf[js.Promise[js.Any]]

  def onPushNotificationReceived(cb: PushNotificationSchema => Unit): js.Promise[js.Any] =
    plugin
      .addListener(
        "pushNotificationReceived",
        { (data: js.Any) => cb(data.asInstanceOf[PushNotificationSchema]) }: js.Function1[
          js.Any,
          Unit,
        ],
      )
      .asInstanceOf[js.Promise[js.Any]]

  def onPushNotificationActionPerformed(cb: ActionPerformed => Unit): js.Promise[js.Any] =
    plugin
      .addListener(
        "pushNotificationActionPerformed",
        { (data: js.Any) => cb(data.asInstanceOf[ActionPerformed]) }: js.Function1[js.Any, Unit],
      )
      .asInstanceOf[js.Promise[js.Any]]
