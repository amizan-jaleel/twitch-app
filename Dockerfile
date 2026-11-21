FROM node:22-bookworm-slim@sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d AS node

# ── Build stage ─────────────────────────────────────────────────────
# November 2026 CSPU: includes the CVE-2026-114938 (Java4Shell) fix.
FROM eclipse-temurin:21.0.13.1_2-jdk-noble@sha256:4dea56dcfa1a89832caf1ffe082c49b3878388df3ce97b7f0a49f9c4372b718c AS build

# Install Node.js and sbt
COPY --from=node /usr/local /usr/local
# Disable retries while the old APT binary bootstraps its CVE-2026-109518 patch.
RUN apt-get -o Acquire::Retries=0 update && \
    apt-get -o Acquire::Retries=0 install -y --no-install-recommends apt libapt-pkg6.0t64 ca-certificates curl gnupg && \
    install -d -m 0755 /etc/apt/keyrings && \
    curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | gpg --dearmor -o /etc/apt/keyrings/sbt.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/sbt.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    apt-get update && apt-get install -y --no-install-recommends sbt && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Cache sbt and npm dependencies first (these change less often)
COPY project/build.properties project/plugins.sbt project/
COPY build.sbt .
COPY package.json package-lock.json ./
COPY plugins/capacitor-firebase-messaging-ios/package.json plugins/capacitor-firebase-messaging-ios/package.json
RUN sbt update && npm ci

# Copy source and build everything
COPY . .
RUN sbt frontend/fullLinkJS backend/assembly

# Assemble static assets into a flat directory
RUN mkdir -p /app/static/icons && \
    cp modules/frontend/target/scala-3.6.3/frontend-opt/main.js /app/static/main.js && \
    cp modules/frontend/dist/output.css /app/static/output.css && \
    cp modules/frontend/manifest.json /app/static/manifest.json && \
    cp modules/frontend/sw.js /app/static/sw.js && \
    cp modules/frontend/icons/icon.svg /app/static/icons/icon.svg && \
    sed -e 's|target/scala-3.6.3/frontend-fastopt/main.js|main.js|' -e 's|dist/output.css|output.css|' modules/frontend/index.html > /app/static/index.html

# ── Run stage ──────────────────────────────────────────────────────
FROM eclipse-temurin:21.0.13.1_2-jre-noble@sha256:48c8fa59c875b31be10ca14343c1992cabdaf15164a5ace3d823fe4341a14573

WORKDIR /app

# Disable retries while the old APT binary bootstraps its CVE-2026-109518 patch.
RUN apt-get -o Acquire::Retries=0 update && \
    apt-get -o Acquire::Retries=0 install -y --no-install-recommends apt libapt-pkg6.0t64 postgresql-client && \
    rm -rf /var/lib/apt/lists/* && \
    chown ubuntu:ubuntu /app

COPY --from=build /app/modules/backend/target/scala-3.6.3/twitch-app.jar /app/twitch-app.jar
COPY --from=build /app/static /app/static

ENV STATIC_DIR=/app/static
ENV PORT=8080

EXPOSE 8080

# App files stay root-owned and read-only; /app itself is writable so the
# local H2 fallback can create its database file when DATABASE_URL is unset.
USER ubuntu

# Exit on heap exhaustion so the platform restarts the process instead of
# leaving it up in a broken state.
CMD ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/twitch-app.jar"]
