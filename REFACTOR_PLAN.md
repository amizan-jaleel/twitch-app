# Refactor Plan: Functional Core / Imperative Shell

## Context

The codebase is ~70% of the way to the "Functional Core, Imperative Shell" architecture pattern. `StreamLogic.scala` and `Models.scala` form a genuine pure functional core, and the IO-heavy files (`Database`, `EmailService`, `TwitchServer`) are correctly in the imperative shell. However:

1. **Purity gaps:** `Routes.scala` and `StreamPoller.scala` have domain logic mixed into their IO chains.
2. **Concurrency bug:** `PushNotificationService` uses a mutable `var` outside the effect system.
3. **Large objects:** `Routes.scala` (319 lines) owns auth, sessions, preferences, search proxying, push registration, and SSE. `Database.scala` (321 lines) owns every table.
4. **No service abstractions:** External services are concrete classes with HTTP logic baked in. Both `Routes` and pollers construct Twitch HTTP requests directly.
5. **Monolith bootstrap:** `TwitchServer.run` is a single 100-line `for`-comprehension.
6. **Frontend state:** `Model.scala` is a flat 25-field bag; components mix API calls with rendering.

Note: The push fanout bug (push only reaching SSE-connected users) was fixed in PR #22 and is not part of this plan.

---

## Phase 1: Move notification filtering into `StreamLogic`

**Problem:** `StreamPoller.filteredNotificationsForUser` (lines 43-55) is a pure function (takes plain values, returns plain values) hiding inside the imperative shell. It already delegates to `StreamLogic.applyTagFilters` and `StreamLogic.applyIgnoredStreamers`.

**Files to modify:**
- `modules/backend/src/main/scala/com/twitch/backend/StreamLogic.scala` — add the function
- `modules/backend/src/main/scala/com/twitch/backend/StreamPoller.scala` — replace private method with `StreamLogic.filteredNotificationsForUser`

**Changes:**

Add to `StreamLogic`:
```scala
def filteredNotificationsForUser(
    userId: String,
    byCategoryId: Map[String, List[StreamNotification]],
    followedMap: Map[String, Set[String]],
    filtersMap: Map[String, List[TagFilter]],
    ignoredMap: Map[String, Set[String]]
): List[StreamNotification] =
  val userCategoryIds = followedMap.getOrElse(userId, Set.empty)
  val relevantNotifications = userCategoryIds.flatMap(id => byCategoryId.getOrElse(id, Nil)).toList
  applyIgnoredStreamers(
    applyTagFilters(relevantNotifications, filtersMap.getOrElse(userId, Nil)),
    ignoredMap.getOrElse(userId, Set.empty)
  )
```

In `StreamPoller`, replace `filteredNotificationsForUser(...)` calls with `StreamLogic.filteredNotificationsForUser(...)` and delete the private method.

**Tests:** Add tests in `StreamLogicSpec.scala` covering: correct category intersection, tag filter application, ignored streamer removal, and the combination of all three.

---

## Phase 2: Extract validation into `Validation.scala`

**Problem:** Domain validation rules (tag length, filter types, platforms, required fields) are embedded inside IO route handlers in `Routes.scala` (lines 239-242, 264, 281-282). Not reusable, not testable without HTTP.

**Files to create/modify:**
- `modules/backend/src/main/scala/com/twitch/backend/Validation.scala` — new file
- `modules/backend/src/main/scala/com/twitch/backend/Routes.scala` — replace inline checks

**Changes:**

Create `Validation.scala`:
```scala
package com.twitch.backend

object Validation:
  def validateTag(tag: String): Either[String, String] =
    val trimmed = tag.trim
    if trimmed.isEmpty || trimmed.length > 25 then Left("Tag must be 1-25 characters")
    else Right(trimmed)

  def validateFilterType(ft: String): Either[String, String] =
    if ft == "include" || ft == "exclude" then Right(ft)
    else Left("filterType must be 'include' or 'exclude'")

  def validatePlatform(p: String): Either[String, String] =
    if Set("ios", "android", "web").contains(p) then Right(p)
    else Left("platform must be 'ios', 'android', or 'web'")

  def validateNonEmpty(value: String, fieldName: String): Either[String, String] =
    if value.trim.isEmpty then Left(s"$fieldName is required")
    else Right(value.trim)
```

Update route handlers to call these instead of inlining the checks.

**Tests:** Pure function unit tests in a new `ValidationSpec.scala`. No IO, no HTTP setup.

---

## Phase 3: Fix mutable `var` in `PushNotificationService` with Mutex

**Problem:** `PushNotificationService` (line 32) uses `private var cachedToken` — mutable state outside the effect system. Replacing with a bare `Ref` fixes referential transparency but the get → miss → fetch flow still allows multiple fibers to fetch tokens concurrently. Use `Mutex` to serialize refresh.

**Files to modify:**
- `modules/backend/src/main/scala/com/twitch/backend/PushNotificationService.scala`
- `modules/backend/src/main/scala/com/twitch/backend/TwitchServer.scala` — create and pass Ref + Mutex

**Changes:**

```scala
class PushNotificationService(
    client: Client[IO],
    projectId: String,
    serviceAccountKey: ServiceAccountKey,
    parallelSends: Int,
    db: Database,
    tokenCache: Ref[IO, Option[(String, Instant)]],
    tokenMutex: Mutex[IO]
):

  private def getAccessToken: IO[String] =
    tokenMutex.lock.surround {
      IO.realTimeInstant.flatMap { now =>
        tokenCache.get.flatMap {
          case Some((token, expiry)) if now.isBefore(expiry.minusSeconds(60)) =>
            IO.pure(token)
          case _ =>
            fetchAccessToken.flatMap { case (token, expiresIn) =>
              val expiry = now.plusSeconds(expiresIn)
              tokenCache.set(Some((token, expiry))) *> IO.pure(token)
            }
        }
      }
    }
```

In `TwitchServer.scala`:
```scala
tokenCache <- IO.ref(Option.empty[(String, Instant)])
tokenMutex <- Mutex[IO]
// pass both to PushNotificationService
```

**Tests:** Existing tests should pass unchanged. This is a correctness fix.

---

## Phase 4: Extract service traits + `TwitchApiClient`

**Problem:** External services are concrete classes with HTTP logic baked in. Routes construct Twitch search/user HTTP requests inline. Tests stub at the HTTP client level which is verbose and fragile.

**Scope:** This phase extracts route-level Twitch API calls (search, user lookup) and the OAuth exchange into `TwitchApiClient`. The pollers' stream-fetching and top-games-fetching are left as-is — they have their own app-token management, pagination logic, and `TwitchPoller` base class that don't share an interface with the user-token route calls. Forcing them into the same trait would be a leaky abstraction.

**Files to create/modify:**
- `modules/backend/src/main/scala/com/twitch/backend/Services.scala` — trait definitions
- `modules/backend/src/main/scala/com/twitch/backend/TwitchApiClient.scala` — new, extracts route-level Twitch HTTP calls
- `modules/backend/src/main/scala/com/twitch/backend/PushNotificationService.scala` — implements `PushService`
- `modules/backend/src/main/scala/com/twitch/backend/EmailService.scala` — implements `EmailNotifier`
- `modules/backend/src/main/scala/com/twitch/backend/Routes.scala` — depends on traits
- `modules/backend/src/main/scala/com/twitch/backend/StreamPoller.scala` — depends on `PushService` trait

**Changes:**

Create `Services.scala`:
```scala
trait PushService:
  def sendBatch(subscriptions: List[PushSubscriptionRow], notifications: List[StreamNotification]): IO[Unit]

trait EmailNotifier:
  def sendWelcomeEmail(email: String, displayName: String): IO[Unit]

trait TwitchApi:
  def searchCategories(query: String, after: Option[String], accessToken: String): IO[TwitchSearchCategoriesResponse]
  def searchChannels(query: String, after: Option[String], accessToken: String): IO[TwitchSearchChannelsResponse]
  def getUser(accessToken: String): IO[TwitchUser]
  def exchangeCode(code: String): IO[TwitchTokenResponse]
  def refreshToken(refreshToken: String): IO[TwitchTokenResponse]
```

Extract Twitch HTTP request construction from `Routes.scala` (search, user lookup, OAuth code exchange, token refresh) into `TwitchApiClient extends TwitchApi`.

Update `Routes` constructor params to use trait types instead of concrete classes. `StreamPoller` takes `PushService` trait but keeps its own Twitch HTTP calls via `TwitchPoller` base class.

**Tests:** Tests can now stub at the trait level:
```scala
val noopPush = new PushService { def sendBatch(...) = IO.unit }
```

---

## Phase 5: Split `Routes.scala` by feature

**Problem:** `Routes.scala` (319 lines) is a single class owning auth, session management, preferences, search proxying, push registration, and SSE. This is the main architectural pressure point.

**Current endpoint inventory** (every existing endpoint must be accounted for):
```
Under "/" prefix:
  GET  /auth/login              → OAuth authorize redirect
  GET  /auth/callback           → OAuth callback

Under "/api" prefix:
  GET  /config                  → Twitch client ID (no session required)
  GET  /user                    → Current user info
  POST /logout                  → Delete session
  GET  /followed                → User's followed categories
  POST /follow                  → Add category
  POST /unfollow/{catId}        → Remove category
  GET  /search/categories       → Search Twitch categories
  GET  /search/channels         → Search Twitch channels
  GET  /tag-filters             → User's tag filters
  POST /tag-filters/add         → Add filter
  POST /tag-filters/remove      → Remove filter
  GET  /ignored-streamers       → Ignored streamers list
  POST /ignored-streamers/add   → Add ignored
  POST /ignored-streamers/remove → Remove ignored
  POST /push/register           → Register device token
  POST /push/unregister         → Unregister device token
  GET  /notifications/stream    → SSE stream
  GET  /top-game-ids            → Top 200 game IDs
```

**Files to create/modify:**
- `modules/backend/src/main/scala/com/twitch/backend/routes/AuthRoutes.scala` — OAuth login/callback (mounted under `/`)
- `modules/backend/src/main/scala/com/twitch/backend/routes/UserRoutes.scala` — `GET /user`, `GET /config`, `POST /logout` (mounted under `/api`)
- `modules/backend/src/main/scala/com/twitch/backend/routes/PreferenceRoutes.scala` — follow/unfollow, tag filters, ignored streamers (mounted under `/api`)
- `modules/backend/src/main/scala/com/twitch/backend/routes/SearchRoutes.scala` — category/channel search (mounted under `/api`)
- `modules/backend/src/main/scala/com/twitch/backend/routes/NotificationRoutes.scala` — SSE stream, push register/unregister, top-game-ids (mounted under `/api`)
- `modules/backend/src/main/scala/com/twitch/backend/auth/SessionManager.scala` — session validation, token refresh via `TwitchApi`, cookie handling
- Delete `modules/backend/src/main/scala/com/twitch/backend/Routes.scala`

**Changes:**

Extract `SessionManager`. It depends on `TwitchApi` for token refresh instead of raw `Client[IO]`, avoiding duplication of Twitch HTTP logic:
```scala
class SessionManager(db: Database, twitchApi: TwitchApi):
  def getSession(req: Request[IO]): IO[Option[SessionData]] = ...
  def refreshTokenIfNeeded(data: SessionData): IO[SessionData] =
    // uses twitchApi.refreshToken(...) instead of constructing HTTP request
  def createSessionCookie(sessionId: String, secure: Boolean): ResponseCookie = ...
```

Each route file takes only the dependencies it needs:
- `UserRoutes` needs `SessionManager` + `Database` (for logout)
- `PreferenceRoutes` needs `SessionManager` + `Database`
- `SearchRoutes` needs `SessionManager` + `TwitchApi`
- `AuthRoutes` needs `SessionManager` + `TwitchApi` + `EmailNotifier` + `Database` + `OAuthStateTokenService` + auth config (`clientId`, `redirectUri`, `secureCookies`). The callback handler does signed OAuth state validation, code exchange, user lookup, user insert/update, welcome email, and session creation — all of which need explicit dependencies. `SessionManager` owns session lookup/refresh/cookies but not session *creation* or user upsert, which belong to the auth flow.
- `NotificationRoutes` needs `SessionManager` + `Database` + notification queues

Compose in `TwitchServer` / `AppWiring`:
```scala
val httpApp = Router(
  "/api" -> (userRoutes <+> preferenceRoutes <+> searchRoutes <+> notificationRoutes),
  "/" -> (authRoutes <+> indexRoute <+> staticFiles)
).orNotFound
```

**Tests:** Update `RoutesSpec` to test each route group independently.

---

## Phase 6: Split persistence into repositories

**Problem:** `Database.scala` (321 lines) owns every table — follows, filters, sessions, users, push subscriptions, top games, ignored streamers. No separation by domain concern.

**Files to create/modify:**
- `modules/backend/src/main/scala/com/twitch/backend/db/FollowRepository.scala` — follow/unfollow, getAllFollowed, getUsersFollowingCategories
- `modules/backend/src/main/scala/com/twitch/backend/db/TagFilterRepository.scala` — add/remove/get tag filters
- `modules/backend/src/main/scala/com/twitch/backend/db/SessionRepository.scala` — CRUD sessions
- `modules/backend/src/main/scala/com/twitch/backend/db/UserRepository.scala` — user CRUD, welcome email flag
- `modules/backend/src/main/scala/com/twitch/backend/db/PushSubscriptionRepository.scala` — push token CRUD
- `modules/backend/src/main/scala/com/twitch/backend/db/IgnoredStreamerRepository.scala` — ignored streamers
- `modules/backend/src/main/scala/com/twitch/backend/db/TopGamesRepository.scala` — top games cache
- `modules/backend/src/main/scala/com/twitch/backend/db/Schema.scala` — `initDb` and all DDL
- Delete `modules/backend/src/main/scala/com/twitch/backend/Database.scala`

Each repository takes the `Transactor[IO]` and `SqlDialect`. Route and service files depend on only the repositories they need.

Note: Production schema evolution is still handled by startup DDL (`CREATE TABLE IF NOT EXISTS`, `ALTER TABLE ... IF NOT EXISTS`). A proper migration tool (e.g. Flyway) is deferred — it's a separate concern from the modularity refactor and would add a new dependency. The `Schema.scala` extraction makes a future migration tool adoption easier by centralizing all DDL in one place.

**Tests:** Split `DatabaseSpec` into per-repository test files.

---

## Phase 7: Slim `TwitchServer.run` into `AppWiring`

**Problem:** `TwitchServer.run` is a single 100-line `for`-comprehension. Hard to test startup or run subsets.

**Files to modify:**
- `modules/backend/src/main/scala/com/twitch/backend/TwitchServer.scala`

**Changes:**

Extract `ServerConfig` (not `AppConfig` — that name is taken in core for frontend config):
```scala
case class ServerConfig(
    clientId: String,
    clientSecret: String,
    baseUrl: String,
    dbUrl: String,
    dbUser: Option[String],
    dbPassword: Option[String],
    port: Int,
    staticDir: String,
    dialect: SqlDialect
)

object ServerConfig:
  def fromEnv: ServerConfig = ...
```

Extract `AppWiring`:
```scala
object AppWiring:
  case class App(httpApp: HttpApp[IO], poller: StreamPoller, topGamesPoller: TopGamesPoller)

  def build(config: ServerConfig, settings: AppSettings): Resource[IO, App] =
    for
      xa <- buildTransactor(config)
      // ... wire repositories, services, routes
    yield App(httpApp, poller, topGamesPoller)
```

`TwitchServer.run` becomes:
```scala
def run: IO[Unit] =
  val config = ServerConfig.fromEnv
  AppWiring.build(config, settings).use { app =>
    (app.poller.start, app.topGamesPoller.start, startServer(app.httpApp)).parTupled.void
  }
```

---

## Phase 8: Frontend state cleanup

**Problem:** `Model.scala` is a flat 25-field case class mixing search, notification, tag filter, and ignored streamer state. Components like `SearchSection` (227 lines) make API calls directly and manage local state alongside rendering.

**Files to modify:**
- `modules/frontend/src/main/scala/com/twitch/frontend/Model.scala` — split into sub-models
- `modules/frontend/src/main/scala/com/twitch/frontend/components/*.scala` — move API calls out of view builders
- `modules/frontend/src/main/scala/com/twitch/frontend/ApiClient.scala` — ensure all API calls go through here

**Changes:**

Split `Model` into feature sub-states:
```scala
case class SearchState(
    query: String = "",
    results: Vector[TwitchCategory] = Vector.empty,
    selectedCategoryIds: Set[String] = Set.empty,
    paginationCursor: Option[String] = None,
    currentPage: Int = 0,
    pageSize: Int = Defaults.SearchPageSize
)

case class NotificationState(
    notifications: List[StreamNotification] = Nil
)

case class TagFilterState(
    filters: List[TagFilter] = Nil,
    newIncludeTag: String = "",
    newExcludeTag: String = ""
)

case class IgnoredStreamerState(
    streamers: List[IgnoredStreamer] = Nil,
    searchQuery: String = "",
    searchResults: List[TwitchChannel] = Nil
)

case class Model(
    status: Option[String] = None,
    user: Option[TwitchUser] = None,
    twitchClientId: Option[String] = None,
    followedCategories: List[TwitchCategory] = Nil,
    search: SearchState = SearchState(),
    notifications: NotificationState = NotificationState(),
    tagFilters: TagFilterState = TagFilterState(),
    ignoredStreamers: IgnoredStreamerState = IgnoredStreamerState(),
    topGameIds: Set[String] = Set.empty,
    pendingPopularFollow: Option[TwitchCategory] = None
)
```

Introduce action modules that orchestrate `ApiClient` calls + `SignallingRef` state updates. `ApiClient` stays as plain HTTP methods (request in, response out). Actions own the workflow:

```scala
// actions/SearchActions.scala
object SearchActions:
  def search(state: SignallingRef[IO, Model], query: String): IO[Unit] =
    for
      maybeResults <- ApiClient.searchCategories(query, after = None)
      _ <- state.update(m => m.copy(search = m.search.copy(
        results = maybeResults.fold(Vector.empty)(_.data.toVector)
      )))
    yield ()

// actions/FollowActions.scala
object FollowActions:
  def follow(state: SignallingRef[IO, Model], category: TwitchCategory): IO[Unit] =
    ApiClient.postFollow(category) *>
      state.update(m => m.copy(followedCategories = category :: m.followedCategories))
```

Components become pure rendering functions over signals — they read state and call action functions on events, but never touch `ApiClient` directly.

---

## Execution Order

```
Phase 1  Move notification filtering into StreamLogic    (small, enables testing)
Phase 2  Extract validation                              (isolated, no API changes)
Phase 3  Fix var → Ref + Mutex                           (correctness, changes constructor)
Phase 4  Service traits + TwitchApiClient                (changes constructor signatures)
Phase 5  Split Routes by feature                         (biggest structural backend change)
Phase 6  Split Database into repositories                (structural, builds on Phase 5)
Phase 7  AppWiring                                       (do last backend, touches wiring from 3-6)
Phase 8  Frontend state cleanup                          (independent of backend phases)
```

Phases 1 and 2 can be done in parallel. Phase 8 can be done in parallel with any backend phase. Each phase is one PR/branch.

---

## Verification

After each phase:
1. `sbt test` — all existing tests pass
2. Manual smoke test with `sbt backend/run`:
   - Login via Twitch OAuth
   - Follow/unfollow categories
   - Tag filters add/remove
   - Ignore/unignore streamers
   - SSE notification stream connects
   - Push notifications arrive with browser closed (regression from PR #22)
3. After Phase 3: verify push still works (requires FCM credentials)
4. After Phase 8: verify all frontend interactions in browser
5. After all phases: run `e2e/smoke.spec.ts`
