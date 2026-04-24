package com.twitch.backend

import munit.CatsEffectSuite
import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import doobie.h2.H2Transactor
import org.http4s.*
import org.http4s.Method.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.headers.Location
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import com.twitch.core.*
import scala.concurrent.duration.*

class RoutesSpec extends CatsEffectSuite:

  // ── Shared test fixtures ────────────────────────────────────────────

  private val testUser = TwitchUser("user1", "testlogin", "TestUser", "https://img.test/avatar.png")
  private val testSettings = AppSettings(
    pollerInterval = 60.seconds,
    recentlyLiveWindow = 5.minutes,
    parallelCategories = 5,
    streamsPageSize = 100,
    searchPageSize = 20,
    sseReconnectDelay = 5.seconds,
    emailFrom = "test@example.com",
    emailFromName = "Test App",
    pushParallelSends = 10,
    topGamesCount = 200,
    topGamesPollInterval = 3.hours
  )
  private val testCategory = TwitchCategory("cat1", "Test Game", "https://img.test/art.jpg")

  // Stub client that handles token exchange and user lookup for auth callback tests,
  // and category search for search endpoint tests.
  private val stubClient: Client[IO] = Client.fromHttpApp[IO](
    HttpRoutes.of[IO] {
      case req @ POST -> Root / "oauth2" / "token" =>
        val tokenResp = TwitchTokenResponse("test-access-token", 3600, None, None, "bearer")
        Response[IO](Status.Ok).withEntity(tokenResp).pure[IO]
      case GET -> Root / "helix" / "users" =>
        val usersResp = TwitchUsersResponse(List(testUser))
        Response[IO](Status.Ok).withEntity(usersResp).pure[IO]
      case GET -> Root / "helix" / "search" / "categories" =>
        val searchResp = TwitchSearchCategoriesResponse(
          List(TwitchCategory("found1", "Found Game", "https://img.test/found.jpg")),
          None
        )
        Response[IO](Status.Ok).withEntity(searchResp).pure[IO]
    }.orNotFound
  )

  case class TestEnv(
      routes: Routes,
      db: Database,
      pendingOAuthStates: Ref[IO, Set[String]],
      notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]]
  )

  private val envFixture = ResourceSuiteLocalFixture(
    "test-env",
    for
      ec <- Resource.eval(IO.executionContext)
      xa <- H2Transactor.newH2Transactor[IO](
        "jdbc:h2:mem:routes_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "sa", "", ec
      )
      db = new Database(xa)
      _ <- Resource.eval(db.initDb)
      pendingStates <- Resource.eval(IO.ref(Set.empty[String]))
      notifQueues <- Resource.eval(IO.ref(Map.empty[String, (String, Queue[IO, StreamNotification])]))
      routes = new Routes(
        clientId = "test-client-id",
        clientSecret = "test-client-secret",
        redirectUri = "http://localhost:8080/auth/callback",
        client = stubClient,
        pendingOAuthStates = pendingStates,
        db = db,
        notificationQueues = notifQueues,
        settings = testSettings,
        emailService = None
      )
    yield TestEnv(routes, db, pendingStates, notifQueues)
  )

  override def munitFixtures = List(envFixture)

  private def env = envFixture()

  private def authApp = env.routes.authRoutes.orNotFound
  private def apiApp = env.routes.apiRoutes.orNotFound

  // Helper: create a session and return the cookie value
  private def createSession: IO[String] =
    val sessionId = java.util.UUID.randomUUID().toString
    env.db.createSession(sessionId, testUser, "test-token", None, None) *>
      IO.pure(sessionId)

  // Helper: build request with session cookie
  private def withSession(req: Request[IO], sessionId: String): Request[IO] =
    req.addCookie("session_id", sessionId)

  // ── Auth gating: protected endpoints return Forbidden without session ──

  test("GET /user returns NotFound when not logged in") {
    for
      resp <- apiApp.run(Request[IO](Method.GET, uri"/user"))
    yield assertEquals(resp.status, Status.NotFound)
  }

  test("GET /followed returns Forbidden when not logged in") {
    for
      resp <- apiApp.run(Request[IO](Method.GET, uri"/followed"))
    yield assertEquals(resp.status, Status.Forbidden)
  }

  test("POST /follow returns Forbidden when not logged in") {
    val body = FollowRequest(testCategory)
    for
      resp <- apiApp.run(Request[IO](Method.POST, uri"/follow").withEntity(body))
    yield assertEquals(resp.status, Status.Forbidden)
  }

  test("POST /unfollow/cat1 returns Forbidden when not logged in") {
    for
      resp <- apiApp.run(Request[IO](Method.POST, uri"/unfollow/cat1"))
    yield assertEquals(resp.status, Status.Forbidden)
  }

  test("GET /tag-filters returns Forbidden when not logged in") {
    for
      resp <- apiApp.run(Request[IO](Method.GET, uri"/tag-filters"))
    yield assertEquals(resp.status, Status.Forbidden)
  }

  test("POST /tag-filters/add returns Forbidden when not logged in") {
    val body = AddTagFilterRequest("include", "english")
    for
      resp <- apiApp.run(Request[IO](Method.POST, uri"/tag-filters/add").withEntity(body))
    yield assertEquals(resp.status, Status.Forbidden)
  }

  test("GET /notifications/stream returns Forbidden when not logged in") {
    for
      resp <- apiApp.run(Request[IO](Method.GET, uri"/notifications/stream"))
    yield assertEquals(resp.status, Status.Forbidden)
  }

  test("GET /search/categories returns Forbidden when not logged in") {
    for
      resp <- apiApp.run(Request[IO](Method.GET, uri"/search/categories?query=test"))
    yield assertEquals(resp.status, Status.Forbidden)
  }

  // ── Auth gating: endpoints succeed with valid session ──

  test("GET /user returns user when logged in") {
    for
      sid <- createSession
      resp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/user"), sid))
      user <- resp.as[TwitchUser]
    yield {
      assertEquals(resp.status, Status.Ok)
      assertEquals(user.id, "user1")
      assertEquals(user.display_name, "TestUser")
    }
  }

  test("GET /config returns client ID without session") {
    for
      resp <- apiApp.run(Request[IO](Method.GET, uri"/config"))
      config <- resp.as[AppConfig]
    yield {
      assertEquals(resp.status, Status.Ok)
      assertEquals(config.twitchClientId, "test-client-id")
    }
  }

  // ── Auth routes: login and callback ─────────────────────────────────

  test("GET /auth/login redirects with state parameter") {
    for
      resp <- authApp.run(Request[IO](Method.GET, uri"/auth/login"))
      pendingStates <- env.pendingOAuthStates.get
    yield {
      assertEquals(resp.status, Status.Found)
      val location = resp.headers.get[Location].get.uri.renderString
      assert(location.contains("id.twitch.tv/oauth2/authorize"), s"Expected Twitch authorize URL, got: $location")
      assert(location.contains("client_id=test-client-id"))
      assert(pendingStates.size == 1, "Expected one pending OAuth state")
    }
  }

  test("GET /auth/callback rejects invalid state") {
    for
      resp <- authApp.run(Request[IO](Method.GET, uri"/auth/callback?code=test-code&state=bad-state"))
    yield assertEquals(resp.status, Status.InternalServerError)
  }

  test("GET /auth/callback with valid state creates session and redirects") {
    for
      // First, do a login to register a state
      loginResp <- authApp.run(Request[IO](Method.GET, uri"/auth/login"))
      pendingStates <- env.pendingOAuthStates.get
      state = pendingStates.head
      callbackResp <- authApp.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/auth/callback?code=test-code&state=$state")))
      setCookie = callbackResp.cookies.find(_.name == "session_id")
      sessionRow <- setCookie.traverse(c => env.db.getSession(c.content))
    yield {
      assertEquals(callbackResp.status, Status.Found)
      assert(setCookie.isDefined, "Expected session_id cookie")
      assert(sessionRow.flatten.exists(_.userId == "user1"), "Expected session with test user in DB")
    }
  }

  // ── Routes + Database: follow/unfollow CRUD ─────────────────────────

  test("POST /follow persists category, GET /followed returns it") {
    for
      sid <- createSession
      followResp <- apiApp.run(
        withSession(Request[IO](Method.POST, uri"/follow").withEntity(FollowRequest(testCategory)), sid)
      )
      followedResp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/followed"), sid))
      body <- followedResp.as[FollowedCategoriesResponse]
    yield {
      assertEquals(followResp.status, Status.Ok)
      assertEquals(followedResp.status, Status.Ok)
      assert(body.categories.exists(_.id == "cat1"), "Expected followed category cat1")
    }
  }

  test("POST /unfollow removes category") {
    val cat = TwitchCategory("cat_unfollow", "Unfollow Me", "https://img.test/art.jpg")
    for
      sid <- createSession
      _ <- apiApp.run(withSession(Request[IO](Method.POST, uri"/follow").withEntity(FollowRequest(cat)), sid))
      unfollowResp <- apiApp.run(withSession(Request[IO](Method.POST, uri"/unfollow/cat_unfollow"), sid))
      followedResp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/followed"), sid))
      body <- followedResp.as[FollowedCategoriesResponse]
    yield {
      assertEquals(unfollowResp.status, Status.Ok)
      assert(!body.categories.exists(_.id == "cat_unfollow"), "Expected category to be unfollowed")
    }
  }

  // ── Routes + Database: tag filter CRUD ──────────────────────────────

  test("POST /tag-filters/add persists filter, GET /tag-filters returns it") {
    for
      sid <- createSession
      addResp <- apiApp.run(
        withSession(Request[IO](Method.POST, uri"/tag-filters/add").withEntity(AddTagFilterRequest("include", "english")), sid)
      )
      filtersResp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/tag-filters"), sid))
      body <- filtersResp.as[TagFiltersResponse]
    yield {
      assertEquals(addResp.status, Status.Ok)
      assertEquals(filtersResp.status, Status.Ok)
      assert(body.filters.exists(f => f.filterType == "include" && f.tag == "english"))
    }
  }

  test("POST /tag-filters/remove deletes filter") {
    for
      sid <- createSession
      _ <- apiApp.run(
        withSession(Request[IO](Method.POST, uri"/tag-filters/add").withEntity(AddTagFilterRequest("exclude", "removeme")), sid)
      )
      removeResp <- apiApp.run(
        withSession(Request[IO](Method.POST, uri"/tag-filters/remove").withEntity(AddTagFilterRequest("exclude", "removeme")), sid)
      )
      filtersResp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/tag-filters"), sid))
      body <- filtersResp.as[TagFiltersResponse]
    yield {
      assertEquals(removeResp.status, Status.Ok)
      assert(!body.filters.exists(_.tag == "removeme"))
    }
  }

  // ── Tag filter validation ───────────────────────────────────────────

  test("POST /tag-filters/add rejects empty tag") {
    for
      sid <- createSession
      resp <- apiApp.run(
        withSession(Request[IO](Method.POST, uri"/tag-filters/add").withEntity(AddTagFilterRequest("include", "   ")), sid)
      )
    yield assertEquals(resp.status, Status.BadRequest)
  }

  test("POST /tag-filters/add rejects tag longer than 25 characters") {
    for
      sid <- createSession
      resp <- apiApp.run(
        withSession(Request[IO](Method.POST, uri"/tag-filters/add").withEntity(AddTagFilterRequest("include", "a" * 26)), sid)
      )
    yield assertEquals(resp.status, Status.BadRequest)
  }

  test("POST /tag-filters/add rejects invalid filterType") {
    for
      sid <- createSession
      resp <- apiApp.run(
        withSession(Request[IO](Method.POST, uri"/tag-filters/add").withEntity(AddTagFilterRequest("invalid", "english")), sid)
      )
    yield assertEquals(resp.status, Status.BadRequest)
  }

  // ── Search categories ───────────────────────────────────────────────

  test("GET /search/categories returns results when logged in") {
    for
      sid <- createSession
      resp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/search/categories?query=test"), sid))
      body <- resp.as[TwitchSearchCategoriesResponse]
    yield {
      assertEquals(resp.status, Status.Ok)
      assertEquals(body.data.head.id, "found1")
    }
  }

  // ── Logout ──────────────────────────────────────────────────────────

  test("POST /logout clears session") {
    for
      sid <- createSession
      logoutResp <- apiApp.run(withSession(Request[IO](Method.POST, uri"/logout"), sid))
      userResp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/user"), sid))
    yield {
      assertEquals(logoutResp.status, Status.Ok)
      val removedCookie = logoutResp.cookies.find(_.name == "session_id")
      assert(removedCookie.isDefined, "Expected session_id cookie removal")
      assertEquals(userResp.status, Status.NotFound, "Session should be invalidated after logout")
    }
  }

  // ── SSE queue registration and cleanup ──────────────────────────────

  test("GET /notifications/stream registers queue for logged-in user") {
    for
      sid <- createSession
      // We need to use the raw routes (not orNotFound) to get the response
      // The SSE endpoint returns a streaming body, so we just check it starts OK
      resp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/notifications/stream"), sid))
      queues <- env.notificationQueues.get
    yield {
      assertEquals(resp.status, Status.Ok)
      assert(queues.contains(sid), "Expected notification queue registered for session")
      val (userId, _) = queues(sid)
      assertEquals(userId, "user1")
    }
  }

  test("SSE queue receives offered notification") {
    for
      sid <- createSession
      resp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/notifications/stream"), sid))
      queues <- env.notificationQueues.get
      (_, queue) = queues(sid)
      notification = StreamNotification("cat1", "Test Game", "u1", "streamer", "Streamer", "Title", 100, "thumb.jpg", List("English"))
      _ <- queue.offer(notification)
      // Read one event from the SSE body
      chunk <- resp.body.through(fs2.text.utf8.decode).take(1).compile.string
    yield {
      assert(chunk.contains("stream-live"), s"Expected SSE event type, got: $chunk")
      assert(chunk.contains("cat1"), s"Expected notification data, got: $chunk")
    }
  }

  // ── Top game IDs endpoint ──────────────────────────────────────────

  test("GET /top-game-ids returns Forbidden when not logged in") {
    for
      resp <- apiApp.run(Request[IO](Method.GET, uri"/top-game-ids"))
    yield assertEquals(resp.status, Status.Forbidden)
  }

  test("GET /top-game-ids returns stored game IDs") {
    val games = List(
      TwitchCategory("game1", "Popular Game 1", "https://img.test/g1.jpg"),
      TwitchCategory("game2", "Popular Game 2", "https://img.test/g2.jpg")
    )
    for
      sid <- createSession
      _ <- env.db.replaceTopGames(games)
      resp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/top-game-ids"), sid))
      body <- resp.as[TopGameIdsResponse]
    yield {
      assertEquals(resp.status, Status.Ok)
      assertEquals(body.gameIds, Set("game1", "game2"))
    }
  }

  test("GET /top-game-ids returns empty set when no top games stored") {
    for
      sid <- createSession
      _ <- env.db.replaceTopGames(Nil)
      resp <- apiApp.run(withSession(Request[IO](Method.GET, uri"/top-game-ids"), sid))
      body <- resp.as[TopGameIdsResponse]
    yield {
      assertEquals(resp.status, Status.Ok)
      assertEquals(body.gameIds, Set.empty[String])
    }
  }

  test("replaceTopGames overwrites previous data") {
    val first = List(TwitchCategory("old1", "Old Game", "https://img.test/old.jpg"))
    val second = List(TwitchCategory("new1", "New Game", "https://img.test/new.jpg"))
    for
      _ <- env.db.replaceTopGames(first)
      _ <- env.db.replaceTopGames(second)
      ids <- env.db.getTopGameIds
    yield assertEquals(ids, Set("new1"))
  }
