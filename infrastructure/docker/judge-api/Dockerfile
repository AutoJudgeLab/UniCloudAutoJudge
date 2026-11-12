# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
COPY modules ./modules
COPY services ./services
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl services/judge-api -am package -DskipTests

FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 judge && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin judge
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/services/judge-api/target/judge-api-*.jar /app/judge-api.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/judge-api.jar"]

