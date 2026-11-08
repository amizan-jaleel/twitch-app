FROM node:22-bookworm-slim@sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d AS node

# ── Build stage ─────────────────────────────────────────────────────
FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d AS build

# Install Node.js and sbt
COPY --from=node /usr/local /usr/local
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg && \
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
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends postgresql-client && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/modules/backend/target/scala-3.6.3/twitch-app.jar /app/twitch-app.jar
COPY --from=build /app/static /app/static

ENV STATIC_DIR=/app/static
ENV PORT=8080

EXPOSE 8080

CMD ["java", "-jar", "/app/twitch-app.jar"]
