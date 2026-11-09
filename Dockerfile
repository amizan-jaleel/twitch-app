FROM node:26-bookworm-slim@sha256:add5c17c14752c54d2f3d519ec46a3815684d41c33f198bcb09fb998f7dcbf68 AS node

# ── Build stage ─────────────────────────────────────────────────────
FROM eclipse-temurin:24.0.2_12-jdk-noble@sha256:dacac8e9a0df0d2bd24e702b4431132875c249930b70555ebd7ca285b5bee684 AS build

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
FROM eclipse-temurin:24.0.2_12-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7

WORKDIR /app

# Disable retries while the old APT binary bootstraps its CVE-2026-109518 patch.
RUN apt-get -o Acquire::Retries=0 update && \
    apt-get -o Acquire::Retries=0 install -y --no-install-recommends apt libapt-pkg6.0t64 postgresql-client && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/modules/backend/target/scala-3.6.3/twitch-app.jar /app/twitch-app.jar
COPY --from=build /app/static /app/static

ENV STATIC_DIR=/app/static
ENV PORT=8080

EXPOSE 8080

CMD ["java", "-jar", "/app/twitch-app.jar"]
