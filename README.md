### Twitch App - Scala 3 Scaffolding

A multi-module Scala 3 (v3.6.3) project using Cats-Effect, Tyrian (Scala.js), and Http4s.

#### Project Structure

- `modules/core`: Shared models and logic (Cross-platform JVM/JS).
- `modules/frontend`: Tyrian-based web frontend (Scala.js).
- `modules/backend`: Http4s-based server (JVM).

The project uses a `modules/` directory to separate these three distinct components. This is necessary because:
1. They target different platforms (JVM vs. JavaScript).
2. They have different library dependencies.
3. It prevents frontend code from accidentally using backend-only libraries.

#### How to run in IntelliJ IDEA

1. **Import the project**: Open IntelliJ and select the root directory. It should detect the `build.sbt` file and import it as an sbt project.
2. **Compile everything**: Open the `sbt` tool window on the right and run the `compile` task.

#### Running the Backend

- In IntelliJ, find `modules/backend/src/main/scala/com/twitch/backend/TwitchServer.scala`.
- Right-click the `TwitchServer` object and select "Run 'TwitchServer'".
- The server will start at `http://localhost:8080`.

#### Running the Frontend

1. **Build the JS**: In the sbt shell (inside IntelliJ), run:
   ```sbt
   frontend / fastLinkJS
   ```
2. **Access the App**: Since the backend now serves the frontend, you don't need to open the HTML file directly. Once the backend is running, just go to:
   `http://localhost:8080`

#### iOS / Android Support

For a "Scala as much as possible" approach to mobile:

- Use **Capacitor** (capacitorjs.com).
- You can wrap your compiled Scala.js frontend into a native container.
- This allows you to share almost 100% of your frontend code between Web, iOS, and Android.
- The `core` logic is also shared between the backend and all mobile/web frontends.

#### Twitch Integration

The app now supports Twitch OAuth2 login:

1. **Register your application**:
   - Go to the [Twitch Developer Console](https://dev.twitch.tv/console).
   - Register a new application.
   - Set the **OAuth Redirect URLs** to `http://localhost:8080/auth/callback`.
2. **Configure the Backend**:
   - The backend expects `TWITCH_CLIENT_ID` and `TWITCH_CLIENT_SECRET` environment variables.
   - **Security Note**: `TWITCH_CLIENT_SECRET` is a private key and must **NEVER** be checked into GitHub. `TWITCH_CLIENT_ID` is public (it's visible in the browser), but we still manage it via environment variables for consistency.
   - **In IntelliJ IDEA**:
       1. Open the "Run" menu and select "Edit Configurations...".
       2. Select the configuration for the backend `Main` object (usually under "Application" or "sbt Task").
       3. In the **Environment variables** field, enter:
          `TWITCH_CLIENT_ID=your_client_id;TWITCH_CLIENT_SECRET=your_secret_here`
       4. Replace `your_client_id` and `your_secret_here` with the values from the Twitch Developer Console.
       5. Click **OK** and restart the backend.
3. **How it works**:
   - The frontend (Scala.js) fetches the `TWITCH_CLIENT_ID` from the backend via the `/api/config` endpoint on startup. This avoids hardcoding keys in the source code and ensures you only need to configure them in one place (the backend environment).
   - Click "Login with Twitch" on the frontend.
   - You will be redirected to Twitch to authorize.
   - Twitch redirects back to the backend `/auth/callback`.
   - The backend exchanges the code for a token and fetches your user profile.
   - **Session Management**: The backend generates a unique session ID for each login and stores it in an `HttpOnly` cookie. This ensures that multiple users can use the app from different browsers without seeing each other's data.
   - **Logout**: You can now log out, which clears the session on both the client (cookie) and the server (session store).
- **Persistence**: The app now uses an **H2 database** (stored in `twitch_app_db.mv.db`) to persist your followed categories. This ensures that even if you restart the server, your followed categories remain saved.

#### Functional Programming (FP)

This project is built on pure FP principles:
- **Cats-Effect**: Used for IO management and concurrency.
- **Tyrian**: An Elm-inspired, side-effect-free architecture for the frontend.
- **Http4s**: A functional HTTP library.
- **Circe**: For immutable and safe JSON handling.
