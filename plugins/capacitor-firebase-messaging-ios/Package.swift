// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorFirebaseMessagingIos",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapacitorFirebaseMessagingIos",
            targets: ["CapacitorFirebaseMessagingIos"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.3.0"),
        .package(url: "https://github.com/firebase/firebase-ios-sdk.git", exact: "11.15.0")
    ],
    targets: [
        .target(
            name: "CapacitorFirebaseMessagingIos",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "FirebaseCore", package: "firebase-ios-sdk"),
                .product(name: "FirebaseMessaging", package: "firebase-ios-sdk")
            ],
            path: "ios/Sources/CapacitorFirebaseMessagingIos")
    ]
)
