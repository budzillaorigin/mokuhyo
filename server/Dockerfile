# Tsumugi sync server. Build from the repository root:
#   docker build -f server/Dockerfile -t tsumugi-server .
# (docker compose in this folder does that for you.)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew gradle.properties build.gradle.kts ./
COPY gradle ./gradle
COPY server ./server
# Only the server module: the app modules need the Android SDK, which the image doesn't have.
RUN printf 'rootProject.name = "tsumugi"\npluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\ninclude(":server")\n' > settings.gradle.kts \
    && chmod +x gradlew \
    && ./gradlew --no-daemon :server:installDist -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 tsumugi
COPY --from=build /src/server/build/install/server /opt/tsumugi
USER tsumugi
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /v1/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
ENTRYPOINT ["/opt/tsumugi/bin/server"]
