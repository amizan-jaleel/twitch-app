import Foundation
import Capacitor
@_exported import FirebaseCore
@_exported import FirebaseMessaging

@objc(CapacitorFirebaseMessagingIosPlugin)
public class CapacitorFirebaseMessagingIosPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CapacitorFirebaseMessagingIosPlugin"
    public let jsName = "CapacitorFirebaseMessagingIos"
    public let pluginMethods: [CAPPluginMethod] = []
}
