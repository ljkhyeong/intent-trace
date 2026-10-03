# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN apk add --no-cache curl \
    && addgroup -S -g 10001 intenttrace \
    && adduser -S -D -H -u 10001 -G intenttrace intenttrace

WORKDIR /app
COPY --from=build --chown=intenttrace:intenttrace /workspace/build/libs/intent-trace.jar /app/intent-trace.jar

USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.io.tmpdir=/tmp"

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/intent-trace.jar"]
