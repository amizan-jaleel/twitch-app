import UIKit
import UserNotifications
import Capacitor
import CapacitorFirebaseMessagingIos

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {

    // Notification action/category identifiers shared with the backend APNs payload.
    private static let streamLiveCategory = "STREAM_LIVE"
    private static let ignoreActionId = "IGNORE_STREAMER"
    private static let fcmTokenKey = "fcm_token"
    // Native action handlers run outside the WebView and can't read the session
    // cookie, so they call the backend directly at its production origin (matching
    // the server.url in capacitor.config.ts).
    private static let backendBaseUrl = "https://twitch-app-grn6.onrender.com"

    var window: UIWindow?
    private var hasRegisteredForRemoteNotifications = false
    private var lastPostedFcmToken: String?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        if FirebaseApp.app() == nil {
            if FirebaseOptions.defaultOptions() != nil {
                FirebaseApp.configure()
            } else {
                print("GoogleService-Info.plist not found. iOS Firebase Messaging is disabled.")
            }
        }
        if FirebaseApp.app() != nil {
            Messaging.messaging().delegate = self
        }
        UNUserNotificationCenter.current().delegate = self
        registerNotificationCategories()
        return true
    }

    // Registers the "Ignore streamer" action shown on live notifications. The action
    // runs in the background (no .foreground option) so tapping it never opens the app.
    private func registerNotificationCategories() {
        let ignoreAction = UNNotificationAction(
            identifier: AppDelegate.ignoreActionId,
            title: "Ignore streamer",
            options: [.destructive]
        )
        let category = UNNotificationCategory(
            identifier: AppDelegate.streamLiveCategory,
            actions: [ignoreAction],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        guard FirebaseApp.app() != nil else {
            NotificationCenter.default.post(
                name: .capacitorDidFailToRegisterForRemoteNotifications,
                object: PushRegistrationError.firebaseNotConfigured
            )
            return
        }

        Messaging.messaging().apnsToken = deviceToken
        hasRegisteredForRemoteNotifications = true
        Messaging.messaging().token { token, error in
            if let error = error {
                NotificationCenter.default.post(
                    name: .capacitorDidFailToRegisterForRemoteNotifications,
                    object: error
                )
                return
            }

            guard let token = token else {
                NotificationCenter.default.post(
                    name: .capacitorDidFailToRegisterForRemoteNotifications,
                    object: PushRegistrationError.missingFcmToken
                )
                return
            }

            self.postFcmTokenIfNeeded(token)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NotificationCenter.default.post(name: .capacitorDidFailToRegisterForRemoteNotifications, object: error)
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard hasRegisteredForRemoteNotifications, let fcmToken = fcmToken else { return }
        postFcmTokenIfNeeded(fcmToken)
    }

    private func postFcmTokenIfNeeded(_ fcmToken: String) {
        guard lastPostedFcmToken != fcmToken else { return }
        lastPostedFcmToken = fcmToken
        // Persist so the background notification-action handler can authenticate
        // its ignore-streamer request even when launched cold.
        UserDefaults.standard.set(fcmToken, forKey: AppDelegate.fcmTokenKey)
        NotificationCenter.default.post(
            name: .capacitorDidRegisterForRemoteNotifications,
            object: fcmToken
        )
    }

    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any], fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        // The backend sends iOS pushes as visible APNs alerts; legacy data-only messages fall through.
        if let aps = userInfo["aps"] as? [AnyHashable: Any], aps["alert"] != nil {
            completionHandler(.newData)
            return
        }

        // Legacy data-only FCM message fallback.
        let title = userInfo["title"] as? String ?? "Stream is live!"
        let body = userInfo["body"] as? String ?? ""

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.userInfo = userInfo
        content.categoryIdentifier = AppDelegate.streamLiveCategory

        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { _ in
            completionHandler(.newData)
        }
    }

    // Show notifications even when app is in foreground
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    // Handle notification taps. "Ignore streamer" calls the backend (authenticated by the
    // device's FCM token) without foregrounding the app; tapping the body opens the stream.
    // AppDelegate owns the UNUserNotificationCenterDelegate (handleApplicationNotifications
    // is disabled in capacitor.config), so this runs reliably — even on a background launch
    // — and the completion handler is held until the network request finishes.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo

        switch response.actionIdentifier {
        case AppDelegate.ignoreActionId:
            guard
                let streamerId = userInfo["streamerId"] as? String,
                !streamerId.isEmpty,
                let token = UserDefaults.standard.string(forKey: AppDelegate.fcmTokenKey)
            else {
                completeNotificationAction(completionHandler)
                return
            }
            ignoreStreamer(
                token: token,
                streamerId: streamerId,
                streamerLogin: userInfo["streamerLogin"] as? String ?? "",
                streamerName: userInfo["streamerName"] as? String ?? "",
                completion: completionHandler
            )
        case UNNotificationDefaultActionIdentifier:
            openStream(from: userInfo, completion: completionHandler)
        default:
            completeNotificationAction(completionHandler)
        }
    }

    // Tapping the notification body opens the stream in the Twitch app (replaces the
    // previous JS deep-link, now that notification handling is fully native).
    private func openStream(from userInfo: [AnyHashable: Any], completion: @escaping () -> Void) {
        guard
            let login = userInfo["streamerLogin"] as? String,
            !login.isEmpty,
            let url = URL(string: "twitch://stream/\(login)")
        else {
            completeNotificationAction(completion)
            return
        }
        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:]) { _ in completion() }
        }
    }

    private func ignoreStreamer(token: String, streamerId: String, streamerLogin: String, streamerName: String, completion: @escaping () -> Void) {
        guard let url = URL(string: "\(AppDelegate.backendBaseUrl)/api/push/ignore-streamer") else {
            completeNotificationAction(completion)
            return
        }
        let payload: [String: String] = [
            "token": token,
            "streamerId": streamerId,
            "streamerLogin": streamerLogin,
            "streamerName": streamerName
        ]

        DispatchQueue.main.async {
            var didFinish = false
            var backgroundTask: UIBackgroundTaskIdentifier = .invalid
            var dataTask: URLSessionDataTask?

            let finishNow: () -> Void = {
                guard !didFinish else { return }
                didFinish = true
                dataTask?.cancel()
                if backgroundTask != .invalid {
                    UIApplication.shared.endBackgroundTask(backgroundTask)
                    backgroundTask = .invalid
                }
                completion()
            }
            let finish: () -> Void = {
                if Thread.isMainThread {
                    finishNow()
                } else {
                    DispatchQueue.main.async {
                        finishNow()
                    }
                }
            }

            backgroundTask = UIApplication.shared.beginBackgroundTask(
                withName: "IgnoreStreamerNotificationAction",
                expirationHandler: finish
            )

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.timeoutInterval = 10
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONSerialization.data(withJSONObject: payload)

            dataTask = URLSession.shared.dataTask(with: request) { _, _, _ in
                finish()
            }
            dataTask?.resume()
        }
    }

    private func completeNotificationAction(_ completion: @escaping () -> Void) {
        if Thread.isMainThread {
            completion()
        } else {
            DispatchQueue.main.async {
                completion()
            }
        }
    }

    func applicationWillResignActive(_ application: UIApplication) {
        // Sent when the application is about to move from active to inactive state. This can occur for certain types of temporary interruptions (such as an incoming phone call or SMS message) or when the user quits the application and it begins the transition to the background state.
        // Use this method to pause ongoing tasks, disable timers, and invalidate graphics rendering callbacks. Games should use this method to pause the game.
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        // Use this method to release shared resources, save user data, invalidate timers, and store enough application state information to restore your application to its current state in case it is terminated later.
        // If your application supports background execution, this method is called instead of applicationWillTerminate: when the user quits.
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        // Called as part of the transition from the background to the active state; here you can undo many of the changes made on entering the background.
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        // Restart any tasks that were paused (or not yet started) while the application was inactive. If the application was previously in the background, optionally refresh the user interface.
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Called when the application is about to terminate. Save data if appropriate. See also applicationDidEnterBackground:.
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        // Called when the app was launched with a url. Feel free to add additional processing here,
        // but if you want the App API to support tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }

    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        // Called when the app was launched with an activity, including Universal Links.
        // Feel free to add additional processing here, but if you want the App API to support
        // tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(application, continue: userActivity, restorationHandler: restorationHandler)
    }

}

enum PushRegistrationError: LocalizedError {
    case firebaseNotConfigured
    case missingFcmToken

    var errorDescription: String? {
        switch self {
        case .firebaseNotConfigured:
            return "Firebase is not configured. Add GoogleService-Info.plist to the iOS app target."
        case .missingFcmToken:
            return "Firebase Messaging did not return an FCM token."
        }
    }
}
