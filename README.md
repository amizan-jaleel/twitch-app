## Twitch Stream Notifier

A web app that sends you browser notifications when Twitch streams go live in categories you follow. Built with Scala 3, Scala.js, and Http4s.

### How It Works

1. Log in with your Twitch account.
2. Search for and follow game/category pages you're interested in.
3. Leave the tab open — you'll get a browser notification whenever a new stream goes live in any of your followed categories.

The backend polls the Twitch API every 60 seconds and pushes updates to connected clients via Server-Sent Events (SSE). Followed categories and sessions are persisted in a database, so they survive server restarts.

### Project Structure

- `modules/core` — Shared models (cross-compiled JVM/JS)
- `modules/frontend` — Scala.js frontend using Calico and Tailwind CSS (via Scalawind)
- `modules/backend` — Http4s server (JVM)

### Prerequisites

Install all of the following before building (works on macOS, Linux, or Windows):

| Tool | Version | Install |
|------|---------|---------|
| JDK | 17+ | [Adoptium](https://adoptium.net/) or `brew install openjdk@17` |
| sbt | latest | [sbt download](https://www.scala-sbt.org/download) or `brew install sbt` |
| Node.js | 20+ | [nodejs.org](https://nodejs.org/) or `brew install node` |

Node.js includes `npm` and `npx` automatically — no separate install needed. They're used by the build for Tailwind CSS and Scalawind generation. `npm install` runs automatically on first build.

### Running Locally

1. Register an app on the [Twitch Developer Console](https://dev.twitch.tv/console) with redirect URL `http://localhost:8080/auth/callback`.

2. Start the server using one of the options below, then open http://localhost:8080.

#### Option A: H2 (simplest, no setup)

No database to install — the app uses an embedded H2 file database automatically:

```sh
TWITCH_CLIENT_ID=your_client_id \
TWITCH_CLIENT_SECRET=your_client_secret \
sbt dev
```

Data is stored in `./twitch_app_db.mv.db` and persists across restarts.

#### Option B: Local PostgreSQL

To develop against the same database used in production:

```sh
# Start a Postgres container
docker run -d --name pg-local \
  -e POSTGRES_PASSWORD=test \
  -e POSTGRES_DB=twitch_app \
  -p 5432:5432 postgres:16

# Start the app pointing at it
TWITCH_CLIENT_ID=your_client_id \
TWITCH_CLIENT_SECRET=your_client_secret \
DATABASE_URL=jdbc:postgresql://localhost:5432/twitch_app \
DATABASE_USER=postgres \
DATABASE_PASS=test \
sbt dev
```

Tables are created automatically on first startup. To stop and remove the container later: `docker stop pg-local && docker rm pg-local`.

### Environment Variables

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `TWITCH_CLIENT_ID` | Yes | — | Twitch OAuth app ID |
| `TWITCH_CLIENT_SECRET` | Yes | — | Twitch OAuth app secret |
| `DATABASE_URL` | No | H2 file DB | JDBC connection string (e.g. `jdbc:postgresql://host:5432/db`) |
| `DATABASE_USER` | No | — | DB username (if not embedded in URL) |
| `DATABASE_PASS` | No | — | DB password (if not embedded in URL) |
| `BASE_URL` | No | `http://localhost:8080` | Public URL (sets redirect URI and cookie security) |
| `PORT` | No | `8080` | Server listen port |
| `STATIC_DIR` | No | `./modules/frontend` | Path to static assets directory |
| `SENDGRID_API_KEY` | No | — | SendGrid API key (enables welcome emails; see [DEPLOY_PLAN.md](DEPLOY_PLAN.md)) |

### Running with Docker

```sh
docker build -t twitch-notifier .
docker run -p 8080:8080 \
  -e TWITCH_CLIENT_ID=your_client_id \
  -e TWITCH_CLIENT_SECRET=your_client_secret \
  -e DATABASE_URL=jdbc:postgresql://host:5432/twitch_app \
  twitch-notifier
```

### Mobile Apps (Android & iOS)

The app ships as a native mobile app via [Capacitor](https://capacitorjs.com/). The WebView loads the production server, while native plugins handle push notifications. Capacitor's CLI (`npx cap`) is installed as a project dependency — running `npm install` is all that's needed.

#### Android

**Prerequisites:**

| Tool | Install |
|------|---------|
| Android Studio | [developer.android.com/studio](https://developer.android.com/studio) |
| JDK 17+ | Bundled with Android Studio, or install separately |

**Setup:**

```sh
# Install JS dependencies (if not done already)
npm install

# Generate the Capacitor project (only needed once, or after deleting android/)
npx cap add android

# Sync web assets and config
npx cap sync android

# Open in Android Studio
npx cap open android
```

In Android Studio: select a device/emulator from the toolbar and click the Run button (green triangle).

A fresh `npx cap add android` will overwrite custom native code — restore from git if that happens.

#### iOS (requires macOS)

**Prerequisites:**

| Tool | Install |
|------|---------|
| Xcode | [Mac App Store](https://apps.apple.com/app/xcode/id497799835) (free, ~12GB) |
| Apple Developer account | [developer.apple.com/programs](https://developer.apple.com/programs/) ($99/year, required for device testing and App Store) |

After installing Xcode, run once:
```sh
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```
(Adjust the path if Xcode is installed elsewhere.)

**Setup:**

```sh
# Install JS dependencies (if not done already)
npm install

# Generate the Capacitor project (only needed once, or after deleting ios/)
npx cap add ios

# Sync web assets and config
npx cap sync ios

# Open in Xcode
npx cap open ios
```

In a fresh clone, run `./build-mobile.sh` before opening Xcode or pressing Run. The Xcode project references generated Capacitor resources (`App/public`, `App/capacitor.config.json`, and `App/config.xml`) that are intentionally ignored and refreshed by the build script.

In Xcode: select a simulator or physical device from the toolbar dropdown and press Cmd+R to build and run.

**Testing on a physical iPhone:**
1. Connect via USB
2. On the iPhone: Settings > Privacy & Security > Developer Mode > enable (requires restart)
3. In Xcode: Settings > Accounts > sign in with your Apple ID and select your development team
4. Select your phone from the device dropdown, then Cmd+R

**Push notifications:** iOS 16+ simulators on macOS 13+ with Apple silicon or a T2 chip can receive APNs sandbox notifications. A physical iPhone is still required before trusting TestFlight/App Store production delivery.

**APNs setup (required for iOS push):**
1. In [Apple Developer](https://developer.apple.com/account/resources/authkeys/list) > Keys > create a new key with APNs enabled, download the `.p8` file
2. In [Firebase Console](https://console.firebase.google.com) > Project Settings > Cloud Messaging > Apple app configuration > upload the `.p8` key with your Key ID and Team ID
3. In Apple Developer > Certificates, Identifiers & Profiles > Identifiers, confirm the App ID `com.twitchnotify.app` has Push Notifications enabled
4. Add the iOS app to Firebase with bundle ID `com.twitchnotify.app`
5. Download the Firebase `GoogleService-Info.plist` and place it at `ios/App/App/GoogleService-Info.plist`
6. Do not commit the real plist. `ios/App/App/GoogleService-Info.plist.example` documents the expected file shape, and the Xcode target copies the real local plist into the app bundle when it is present.
7. Build and run on a physical iPhone or TestFlight build, allow notifications, then confirm `/api/push/register` receives an FCM registration token for platform `ios`.

**Uploading to TestFlight:**

Use the one-shot script — it runs the frontend build, archives, signs, verifies, and (optionally) uploads:

```bash
scripts/build-ios-testflight.sh            # build + verify locally
scripts/build-ios-testflight.sh --upload   # build + verify + upload to TestFlight
```

It auto-increments the build number (tracked in `ios/build-number.txt`) and refuses to upload unless `scripts/verify-ios-ipa.sh` confirms the IPA reports `aps-environment: production` and a bundled `GoogleService-Info.plist`. Prerequisite: `ios/App/App/GoogleService-Info.plist` present locally.

For signing/upload auth, configure an App Store Connect API key (recommended — headless and durable, unlike a Xcode Apple ID session that expires): copy `scripts/.asc-api-key.env.example` to `scripts/.asc-api-key.env` (gitignored) and fill in the key. Without it, the script falls back to the Apple ID signed into Xcode (Settings > Accounts).

Why a script instead of "Archive in Xcode"? Three non-obvious traps it handles for you:

- **Homebrew `rsync` breaks the export.** Xcode's IPA packaging calls `/usr/bin/rsync -E`, but a Homebrew `rsync` earlier on `PATH` rejects Apple's `--extended-attributes` and fails with "Copy failed". The script forces `/usr/bin` first.
- **A signed archive can't be produced here.** Automatic signing wants an iOS *Development* profile (needs a registered device — the team has none), ad-hoc signing is blocked on the current SDK, and the Distribution cert is cloud-managed (no local private key). An App Store Connect API key does **not** change this (the archive still demands a Development profile). So the script archives **unsigned** and lets `exportArchive` do the real (cloud) Distribution signing.
- **The unsigned archive loses `aps-environment`.** An unsigned archive carries no entitlements, so the export re-derives them from the provisioning profile — which omits `aps-environment`, silently shipping a build with **no push**. The script first `codesign`-embeds `ios/appstore-entitlements.plist`, which the export then preserves.

**iOS push architecture:** `AppDelegate.swift` configures Firebase, maps the APNs device token into Firebase Messaging, then forwards the Firebase Messaging registration token through Capacitor's push registration event. The backend sends iOS subscriptions as visible FCM/APNs alert pushes with `apns-push-type: alert` and `apns-priority: 10`, while Android keeps the existing data-only payload path. Verify foreground, background, and terminated delivery on a real iPhone before relying on production notifications.

### Tech Stack

- **Scala 3** with Cats-Effect for concurrency
- **Http4s** for the backend HTTP server and API
- **Calico** (Scala.js) for the reactive frontend
- **Scalawind** + **Tailwind CSS** for styling
- **Doobie** + **PostgreSQL** for production persistence (H2 for local dev)
- **Circe** for JSON serialization
