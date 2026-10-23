# syntax=docker/dockerfile:1
# Multi-arch (linux/amd64, linux/arm64). The Gradle build runs once on the *build* platform
# (JVM bytecode is arch-independent); only the small runtime stage is per-arch.

# ---- build ----
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# settings.gradle.kts includes the Windows updater subproject; only its build script is needed to configure.
COPY updater/build.gradle.kts ./updater/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon :installDist -x test

# ---- runtime ----
FROM eclipse-temurin:21-jre
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="couple-backend" \
      org.opencontainers.image.source="https://github.com/slss15/couple-" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
# curl is used by HEALTHCHECK; installed explicitly instead of relying on the base image.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system couple && useradd --system --gid couple --home /app couple \
 && mkdir -p /data/releases && chown -R couple:couple /data
WORKDIR /app
COPY --from=build /src/build/install/couple-backend /app
USER couple
ENV PORT=8080 \
    APP_VERSION=${VERSION} \
    APP_REVISION=${REVISION} \
    RELEASES_DIR=/data/releases \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
# Mirrored APKs (release mirror). Mount a named volume here so they survive image updates.
VOLUME ["/data/releases"]
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/api/v1/health" || exit 1
ENTRYPOINT ["/app/bin/couple-backend"]
