# syntax=docker/dockerfile:1
# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN groupadd --system couple && useradd --system --gid couple --home /app couple
WORKDIR /app
COPY --from=build /src/build/install/couple-backend /app
USER couple
ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/api/v1/health" || exit 1
ENTRYPOINT ["/app/bin/couple-backend"]
