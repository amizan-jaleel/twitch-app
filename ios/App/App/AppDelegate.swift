import UIKit
import UserNotifications
import Capacitor
import CapacitorFirebaseMessagingIos

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {

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
        return true
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

        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { _ in
            completionHandler(.newData)
        }
    }

    // Show notifications even when app is in foreground
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
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
