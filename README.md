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

- In IntelliJ, find `modules/backend/src/main/scala/com/example/backend/Main.scala`.
- Right-click the `Main` object and select "Run 'Main'".
- The server will start at `http://localhost:8080`.
- You can test the API: `curl http://localhost:8080/ping`.

#### Running the Frontend

1. **Build the JS**: In the sbt shell (inside IntelliJ), run:
   ```sbt
   frontend / fastLinkJS
   ```
2. **Open in Browser**: Open `modules/frontend/index.html` in your browser.
   - Note: In development, the `index.html` is configured to look for the JS file in `target/scala-3.3.7/frontend-fastopt/main.js`.

#### iOS / Android Support

For a "Scala as much as possible" approach to mobile:

- Use **Capacitor** (capacitorjs.com).
- You can wrap your compiled Scala.js frontend into a native container.
- This allows you to share almost 100% of your frontend code between Web, iOS, and Android.
- The `core` logic is also shared between the backend and all mobile/web frontends.

#### Functional Programming (FP)

This project is built on pure FP principles:
- **Cats-Effect**: Used for IO management and concurrency.
- **Tyrian**: An Elm-inspired, side-effect-free architecture for the frontend.
- **Http4s**: A functional HTTP library.
- **Circe**: For immutable and safe JSON handling.
