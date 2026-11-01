# syntax=docker/dockerfile:1
# One multi-stage Dockerfile for all three services; build from the repository root:
#   docker build -f infra/docker/Dockerfile --build-arg SERVICE=case-service --build-arg PORT=8081 -t content-review/case-service:local .

FROM eclipse-temurin:21.0.10_7-jdk-noble AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -q -pl services/${SERVICE} -am -DskipTests package \
    && cp services/${SERVICE}/target/${SERVICE}-*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:21.0.10_7-jre-noble
ARG PORT
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home app
COPY --from=build /app.jar /app/app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true
EXPOSE ${PORT}
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
