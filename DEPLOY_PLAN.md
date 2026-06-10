# Plan: Deploy Twitch Stream Notifier as a Production App

## Context

The Twitch Stream Notifier is a working local app (Scala 3 / Http4s / Scala.js / H2) that notifies users when streams go live in their followed categories. The goal is to make it a real, publicly accessible multi-user app. Today, several things are hardcoded for localhost and the stack uses components (H2, in-memory sessions) that don't survive restarts or scale to multiple users in production.

---

## Step 1: Replace H2 with PostgreSQL

**Why:** H2 is an embedded file-based database. It doesn't support concurrent access from multiple processes, has no remote connectivity, and isn't suitable for production hosting. The app already runs H2 in `MODE=PostgreSQL`, so the SQL is largely compatible.

**Files to modify:**
- `build.sbt` — Replace `h2` and `doobie-h2` dependencies with `doobie-postgres` and `org.postgresql:postgresql` JDBC driver
- `modules/backend/src/main/scala/com/twitch/backend/TwitchServer.scala`:
  - Line 32: Change JDBC URL from `jdbc:h2:./twitch_app_db;MODE=PostgreSQL;...` to read a `DATABASE_URL` env var (e.g. `jdbc:postgresql://host:5432/twitch_app`)
  - Lines 33-36: Replace `H2Transactor.newH2Transactor` with a Hikari-backed `HikariTransactor` via `doobie-hikari`. H2Transactor is H2-specific and won't work with Postgres. The Hikari pool also provides connection pooling needed for production.
- `modules/backend/src/main/scala/com/twitch/backend/Database.scala` — Change `MERGE INTO` to standard PostgreSQL `INSERT ... ON CONFLICT ... DO UPDATE` in both the `follow` method (lines 39-42) and `addTagFilter` method (lines 62-66)
- `modules/backend/src/main/scala/com/twitch/backend/Database.scala` (lines 12-29) — Verify `CREATE TABLE IF NOT EXISTS` syntax works as-is with Postgres (it should)

**New env vars:** `DATABASE_URL` (full JDBC connection string, includes credentials)

---

## Step 2: Externalize All Configuration

**Why:** Host, port, redirect URI, and DB path are still hardcoded. A deployed app needs these to come from the environment so the same image works in dev, staging, and production.

**Already done:** Poller intervals, page sizes, and SSE reconnect delay are externalized in `application.conf` and loaded via `AppSettings` (AppSettings.scala). What remains is host, port, redirect URI, and database URL.

**Files to modify:**
- `modules/backend/src/main/scala/com/twitch/backend/TwitchServer.scala`:
  - Line 27: `redirectUri` — read from `BASE_URL` env var, construct as `${BASE_URL}/auth/callback` (default to `http://localhost:8080` for local dev)
  - Line 32: DB URL — already handled in Step 1
  - Lines 46-47: host/port — read `PORT` env var (many hosting platforms set this automatically), default to `8080`
- `modules/frontend/src/main/scala/com/twitch/frontend/components/LoginSection.scala` (line 21):
  - The frontend currently navigates to `/auth/login` (a relative path), so the redirect URI is actually handled server-side already. No frontend change needed for the redirect URI itself.
- `modules/core/src/main/scala/com/twitch/core/Models.scala` (line 39):
  - `AppConfig` currently only carries `twitchClientId`. If we want to expose `redirectUri` or `baseUrl` to the frontend via `/api/config` in the future, add those fields here. Not strictly needed for initial deploy since the OAuth flow is server-side.

**Env var summary after this step:**
| Variable | Required | Example | Purpose |
|----------|----------|---------|---------|
| `TWITCH_CLIENT_ID` | Yes | `vk4vfg8...` | Twitch OAuth app ID |
| `TWITCH_CLIENT_SECRET` | Yes | `ztiknten...` | Twitch OAuth app secret |
| `DATABASE_URL` | Yes | `jdbc:postgresql://localhost:5432/twitch_app` | Postgres connection |
| `BASE_URL` | No | `https://myapp.onrender.com` | Public URL (default: `http://localhost:8080`) |
| `PORT` | No | `8080` | Server listen port (default: `8080`) |

---

## Step 3: Persist Sessions (with Twitch Token Management)

**Why:** Sessions are stored in an in-memory `Ref[IO, Map[String, SessionData]]` (TwitchServer.scala line 42). A server restart loses all sessions, logging everyone out. In production, this is unacceptable.

**Important constraint:** The backend uses the user's Twitch access token to call the Twitch API on their behalf (e.g., `/api/search/categories` at Routes.scala line 132 passes `data.accessToken` to the Twitch Helix API). Any session solution **must** preserve the Twitch access token server-side — a client-only JWT carrying just user identity would break search and any other Twitch API proxy endpoints.

### Recommended: Store sessions in PostgreSQL with token refresh

- Add a `sessions` table:
  ```sql
  CREATE TABLE sessions (
    session_id   VARCHAR PRIMARY KEY,
    user_id      VARCHAR NOT NULL,
    access_token VARCHAR NOT NULL,
    refresh_token VARCHAR,
    token_expires_at TIMESTAMP,
    created_at   TIMESTAMP DEFAULT NOW()
  );
  ```
- Store `refresh_token` from the Twitch token response (`TwitchTokenResponse` already includes `refresh_token: Option[String]` and `expires_in: Int` — currently ignored in Routes.scala line 88)
- Add `createSession`, `getSession`, `deleteSession` methods to `Database.scala`
- Add token refresh logic: before making a Twitch API call, check if the token is near expiry; if so, use the refresh token to obtain a new access token and update the session row
- Add a session TTL / expiry check (e.g. 30 days) and periodic cleanup
- Modify `Routes.scala` to use DB-backed sessions instead of the in-memory Ref

**Why not stateless JWT:** A JWT that only carries user identity (id, display_name) cannot satisfy the `/api/search/categories` endpoint, which needs the Twitch access token. Embedding the Twitch token in the JWT is insecure (tokens in cookies are exposed to XSS if not careful, and JWTs can't be revoked). Server-side sessions are the right call here.

---

## Step 4: Auth Hardening

**Why:** Before going public, the OAuth flow and session cookies need production-grade security.

**Cookie attributes** (Routes.scala line 88):
- The session cookie currently sets `httpOnly = true` but is missing `Secure` and `SameSite` attributes
- Add `secure = true` (ensures cookie is only sent over HTTPS) and `sameSite = SameSite.Lax` (prevents CSRF via cross-site requests)
- Make `secure` conditional: off for `http://localhost` dev, on when `BASE_URL` starts with `https`

**OAuth state parameter:**
- Implemented as a signed, expiring state token plus browser-bound `oauth_state` cookie. This keeps CSRF binding without an in-memory pending-state map.

---

## Step 5: Dockerize the App

**Why:** A Dockerfile lets you deploy to any container hosting platform (Render, Railway, AWS ECS, etc.) with a single `docker build && docker push`.

**Key challenge:** The server currently serves frontend assets from source-tree paths:
- `StaticFile.fromPath(fs2.io.file.Path("./modules/frontend/index.html"))` (TwitchServer.scala line 57)
- `fileService[IO](FileService.Config("./modules/frontend"))` (TwitchServer.scala line 50)
- `index.html` hardcodes `<script src="target/scala-3.6.3/frontend-fastopt/main.js">` (the dev-mode Scala.js output)

The Docker image must either:
- **(A) Reproduce the expected directory structure** inside the image so the existing serving code works (copy `modules/frontend/index.html`, `modules/frontend/dist/output.css`, and the compiled JS to the paths the server expects), OR
- **(B) Refactor the serving strategy** to serve from a single `public/` or `static/` directory, update `index.html` to reference `frontend-opt/main.js` (the `fullLinkJS` production output), and update the server's static file paths accordingly

Option B is cleaner. This means:
- Create a production `index.html` (or templatize it) that references the `fullLinkJS` output path
- Update `TwitchServer.scala` to read a configurable static assets directory (e.g., from `STATIC_DIR` env var, defaulting to `./modules/frontend` for local dev)
- In the Dockerfile build stage, copy the compiled assets into the expected structure

**New files to create:**
- `Dockerfile` — Multi-stage build:
  1. **Build stage:** Use an sbt/JDK image with Node.js. Run `sbt frontend/fullLinkJS backend/assembly` to produce a fat JAR and compiled frontend assets.
  2. **Run stage:** Use a slim JRE image. Copy the JAR and assembled frontend assets into the expected directory structure. Expose `$PORT`.
- `.dockerignore` — Exclude `.bsp/`, `target/`, `node_modules/`, `.idea/`, `*.mv.db`, `*.trace.db`

**Files to modify:**
- `build.sbt` — Add `sbt-assembly` or `sbt-native-packager` plugin for building a deployable artifact. Ensure `fullLinkJS` (optimized JS) is used for production builds instead of `fastLinkJS`.
- `modules/frontend/index.html` — Either templatize or create a production variant that references the correct JS path
- `modules/backend/src/main/scala/com/twitch/backend/TwitchServer.scala` — Make static asset paths configurable

**Build command:** `docker build -t twitch-notifier .`
**Run command:** `docker run -p 8080:8080 --env-file .env twitch-notifier`

---

## Step 5b: Add Docker Build to GitHub Actions

**Why:** Catch Dockerfile issues (broken `COPY` paths, missing build dependencies, failed `sbt assembly`) in CI before they reach Render. This also validates that the multi-stage build produces a working image on every push.

**Files to modify:**
- `.github/workflows/ci.yml` — Add a new job that builds the Docker image after the existing `build-and-test` job passes. This keeps the fast compile+test feedback loop separate from the slower Docker build.

**What the new job does:**
1. Check out the repo
2. Use `docker/setup-buildx-action` for layer caching (speeds up repeat builds)
3. Run `docker build .` — this exercises the full multi-stage build (sbt compile, fullLinkJS, assembly, asset copying)
4. Does **not** push the image anywhere — Render handles that on deploy. This is a build-only validation step.

**Example workflow addition:**
```yaml
  docker-build:
    runs-on: ubuntu-latest
    needs: build-and-test
    steps:
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@8d2750c68a42422c14e847fe6c8ac0403b4cbd6f # v3

      - name: Build Docker image
        uses: docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8 # v6
        with:
          context: .
          push: false
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

**Key details:**
- `needs: build-and-test` — the Docker build only runs if compile + tests pass, so you don't waste CI minutes on a broken build
- `cache-from/cache-to: type=gha` — uses GitHub Actions' built-in cache for Docker layers, so unchanged layers (JDK base image, sbt dependencies) are reused across runs
- `push: false` — we only validate the build, we don't push to a registry
- The path filter in the workflow should be updated to also trigger on `Dockerfile` and `.dockerignore` changes

**Path filter update for `.github/workflows/ci.yml`:**
```yaml
paths:
  - '**.scala'
  - '**.sbt'
  - 'project/**'
  - 'package.json'
  - 'package-lock.json'
  - 'Dockerfile'
  - '.dockerignore'
  - '.github/workflows/**'
```

**When to implement:** After the Dockerfile is created in Step 5. The CI job references the Dockerfile, so it can't be added until the Dockerfile exists.

---

## Step 6: Deploy

**Why:** This is the actual "make it real" step.

**Recommended platform: Render** (~$13/month for web service + managed Postgres)

**Why Render over alternatives:**
- **GUI-first:** Clean dashboard for deploys, logs, env vars, and scaling — no CLI required
- **Managed Postgres:** $6/month Basic tier includes automated daily backups and point-in-time recovery
- **Custom domains:** Free, simple DNS setup, automatic SSL certificate provisioning
- **Support:** Email-ticketed support on the base paid tier (not just community forums)
- **Pricing:** $7/month Starter web service + $6/month Postgres = ~$13/month total

**Alternatives considered:**
| Platform | Why not |
|---|---|
| Fly.io | CLI-first (sparse dashboard), managed Postgres starts at $38/month |
| Railway | Best UI, cheapest (~$8-12), but Postgres is self-hosted (no automated backups/failover) |
| DigitalOcean | Most mature UI, but $20-25/month minimum (Postgres alone is $15/month) |

**Initial deployment is single-instance.** This is important because:
- The `StreamPoller` runs inside the app process (TwitchServer.scala line 65). Every instance would create its own poller, multiplying Twitch API calls. For a single-instance deploy, this is fine.
- In-memory structures (`notificationQueues`) are not shared across instances
- If horizontal scaling is needed later, the poller should be extracted to a separate process or use a leader-election mechanism, and notification delivery should use a pub-sub system (e.g., Redis, Postgres LISTEN/NOTIFY)

**Steps:**
1. Sign up at render.com, create a new **Web Service** and connect the GitHub repo
2. Set the build command to build the Docker image (Render auto-detects the Dockerfile)
3. Create a new **PostgreSQL** database (Basic tier, $6/month)
4. In the web service's Environment settings, add:
   - `DATABASE_URL` — copy the Internal Connection String from the Postgres dashboard
   - `TWITCH_CLIENT_ID` — your Twitch app client ID
   - `TWITCH_CLIENT_SECRET` — your Twitch app secret
   - `BASE_URL` — `https://<app-name>.onrender.com` (or your custom domain)
   - `PORT` — `8080` (or whatever Render assigns via `$PORT`)
5. Add your custom domain in the Render dashboard under Settings > Custom Domains. Add a CNAME record pointing to `<app-name>.onrender.com`. SSL is provisioned automatically.
6. Update Twitch Developer Console: add `https://<your-domain>/auth/callback` as an allowed redirect URI
7. Deploy — Render builds from the Dockerfile on every push to `master`

---

## Step 7: Structured Logging and Metrics

**Why:** The app currently logs via `IO.println` — no log levels, no timestamps, no structured format. In production, you need to distinguish errors from info, search logs efficiently, and understand app health at a glance. Metrics let you answer questions like "how many streams are we polling?" and "how long do Twitch API calls take?" without reading logs.

### Part A: Structured Logging

**Current state:** All logging is `IO.println` (Routes.scala lines 57/78/85/92, StreamPoller.scala lines 105/120/129-131, TwitchServer.scala line 68). Logback is already a dependency (`build.sbt` line 127) but has no configuration file and isn't used by application code.

**What to do:**
- Add `log4cats` dependency to `build.sbt` — this is the cats-effect logging library that integrates with SLF4J/Logback:
  ```scala
  "org.typelevel" %% "log4cats-slf4j" % "2.7.0"
  ```
- Create `modules/backend/src/main/resources/logback.xml` with a structured JSON appender (for production log aggregation) and a human-readable console appender (for local dev). Use an environment variable to switch between them.
- Replace all `IO.println` calls with appropriate log levels:
  | Current `IO.println` | Log level | Why |
  |---|---|---|
  | "Server started at..." | `info` | Startup lifecycle |
  | "StreamPoller: starting..." | `info` | Startup lifecycle |
  | "Poller: seeded N streams..." | `info` | Operational status |
  | "Poller: fetched N streams..." | `debug` | Noisy in production (every 60s) |
  | "Received auth callback" | `debug` | Routine request flow |
  | "Token exchange successful" | `info` | Auth lifecycle |
  | "Found user: ..." | `info` | Auth lifecycle |
  | "Auth flow failed: ..." | `error` | Actionable failure |
  | "StreamPoller seed error: ..." | `error` | Actionable failure |
  | "StreamPoller error: ..." | `error` | Actionable failure |

- Add request logging middleware to the http4s server — log method, path, status, and duration for every request. http4s has built-in `Logger` middleware for this.

### Part B: Application Metrics

**What to track:**
- **Poller metrics:** streams fetched per poll, new streams detected, notifications broadcast, poll duration, Twitch API errors
- **HTTP metrics:** request count by route, response status codes, request latency
- **Session metrics:** active sessions, logins/logouts
- **SSE metrics:** active SSE connections, notifications delivered

**Recommended approach:**
- Add `http4s-prometheus-metrics` (or `http4s-micrometer`) for automatic HTTP request metrics
- Add custom counters/gauges in `StreamPoller` and `Routes` for app-specific metrics
- Expose a `/metrics` endpoint in Prometheus format — Render supports Prometheus scraping, and you can connect a free Grafana Cloud instance for dashboards and alerting

**Files to modify:**
- `build.sbt` — Add `log4cats-slf4j` and metrics library dependencies
- `modules/backend/src/main/resources/logback.xml` — New file for log configuration
- `StreamPoller.scala` — Replace `IO.println` with logger, add poller metrics
- `Routes.scala` — Replace `IO.println` with logger, add session/SSE metrics
- `TwitchServer.scala` — Replace `IO.println` with logger, wire up metrics middleware and `/metrics` endpoint

**When to implement:** After deployment (Step 6). Logging (Part A) is higher priority — do it first. Metrics (Part B) can come later once you want visibility into production behavior.

---

## Step 8: Welcome Emails via SendGrid

**Why:** Send a one-time welcome email when a new user signs up for the first time, thanking them and letting them know how to submit feedback. Without `SENDGRID_API_KEY` set, the app runs normally — emails are simply skipped.

### SendGrid Setup (one-time)

1. **Create a SendGrid account** at https://sendgrid.com (free tier: 100 emails/day)
2. **Create an API key**: Settings → API Keys → Create API Key. Select "Restricted Access" with **Mail Send** permission only (principle of least privilege)
3. **Verify a sender identity** (Settings → Sender Authentication):
   - **For testing**: Single Sender Verification — verify one email address
   - **For production**: Domain Authentication — add CNAME DNS records for your domain. This enables SPF/DKIM, improves deliverability, and removes the "via sendgrid.net" label from emails

### Configuration

4. Update `application.conf` — set `email.from` and `email.from-name` to match your verified sender identity
5. Add `SENDGRID_API_KEY` to your Render web service environment variables (or equivalent in your hosting platform)

### Database

6. No manual migration needed — `CREATE TABLE IF NOT EXISTS users` runs automatically on startup via `initDb`

### Verification

7. Deploy and log in as a new user — check server logs for email send confirmation
8. Verify `welcome_email_sent = true` in the `users` table
9. Log in again — verify no duplicate email is sent

### Rollback

To disable emails without a redeploy: remove the `SENDGRID_API_KEY` env var and restart.

---

## Verification

After each step, verify:
1. **Step 1:** Run the app locally against a local Postgres instance. Confirm login, follow/unfollow, and data persistence across restarts.
2. **Step 2:** Start the server with `BASE_URL=http://localhost:8080 PORT=8080` env vars. Confirm OAuth flow works. Change port to 9090, confirm it binds correctly.
3. **Step 3:** Log in, restart the server, confirm you're still logged in. Verify search still works (Twitch token is preserved). Wait for token expiry and confirm refresh works.
4. **Step 4:** Verify cookies have `Secure` and `SameSite` attributes when accessed over HTTPS. Confirm OAuth state validation still works.
5. **Step 5:** `docker build` succeeds. `docker run` with env vars starts the app and serves the frontend correctly (JS loads, CSS loads, app is interactive).
6. **Step 6:** Visit `https://<your-domain>` (or `https://<app-name>.onrender.com`), log in with Twitch, follow categories, receive notifications.
7. **Step 7a:** Confirm logs show structured output with timestamps and levels. Verify `debug` logs are suppressed in production config. Check that request logging middleware captures method, path, status, and duration.
8. **Step 7b:** Hit `/metrics` and confirm Prometheus-format output. Verify poller gauges update after a poll cycle. Connect Grafana Cloud and confirm data flows.
9. **Step 8:** Set `SENDGRID_API_KEY`, log in as a new user, confirm welcome email arrives. Log in again, confirm no duplicate. Remove env var, restart, confirm app works without it.
