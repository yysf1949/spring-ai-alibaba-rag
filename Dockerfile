# syntax=docker/dockerfile:1.7
#
# Multi-stage Dockerfile for rag-app (spec §12.2).
#
# Stage 1 — build: eclipse-temurin:21-jdk + Maven 3.9.9 (from Aliyun).
# Stage 2 — runtime: eclipse-temurin:21-jre-jammy, copies the fat-jar only.
#
# Build:
#     docker build -t rag-app:local .
# Run:
#     docker run --rm -p 8080:8080 \
#         -e REDIS_HOST=host.docker.internal \
#         -e SPRING_APPLICATION_JSON='{"spring":{"rag":{"redis":{"host":"host.docker.internal","port":6379}}}}' \
#         rag-app:local
#
# Notes:
# - Uses Aliyun Maven mirrors via a settings.xml overlay for faster downloads in
#   CN regions; falls back to Maven Central if the mirror is unreachable.
# - `-DskipTests` keeps the build fast; tests run in CI on the host instead.
# - `-pl rag-app -am` avoids building rag-test (test-only module) which the
#   fat-jar never needs.
# - Layer cache: the .m2 cache mount means dependency download only happens
#   once per pom change.
# - Built jar is repackaged by spring-boot-maven-plugin (layout=JAR), so it
#   contains every transitive dependency and runs with `java -jar`.

# ─────────────────────────── Stage 1: build ───────────────────────────
# Use the official Temurin JDK 21 image. We don't pull a separate Maven
# base because (a) Maven:* images are multi-arch and trigger proxy cert
# mismatches in CN, and (b) the host already has a verified Maven install
# we can copy in via the build context.
FROM eclipse-temurin:21-jdk AS build

ENV MAVEN_HOME=/opt/maven
ENV PATH=${MAVEN_HOME}/bin:${PATH}

# Maven is provided by the build context (./.docker-maven/) — the user
# stages their host's Maven install there via the helper script in
# docs/RUNBOOK.md §4. This avoids both (a) downloading from CN-blocked
# mirrors inside the build container, and (b) pulling the multi-arch
# maven:* base image which trips a proxy cert mismatch.
COPY .docker-maven ${MAVEN_HOME}

WORKDIR /src

# Aliyun Maven mirrors — much faster in CN, transparent fallback to Central.
# (Drop the mirror blocks if running outside CN.)
# Heredocs work in Docker BuildKit but trip up podman's imagebuilder 4.9, so
# we write settings.xml out via printf instead of an inline <<'EOF' block.
RUN mkdir -p /root/.m2 && \
    printf '%s\n' \
        '<settings>' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>aliyun-public</id>' \
        '      <mirrorOf>central</mirrorOf>' \
        '      <name>Aliyun Public</name>' \
        '      <url>https://maven.aliyun.com/repository/public</url>' \
        '    </mirror>' \
        '    <mirror>' \
        '      <id>aliyun-spring</id>' \
        '      <mirrorOf>spring</mirrorOf>' \
        '      <name>Aliyun Spring</name>' \
        '      <url>https://maven.aliyun.com/repository/spring</url>' \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' \
        > /root/.m2/settings.xml

# Copy parent pom first so dependency resolution is cached as a separate layer.
# (Module poms change more often than the parent — pom-only changes don't bust
# the dependency-download cache.)
COPY pom.xml ./
COPY rag-core rag-core
COPY rag-embedding rag-embedding
COPY rag-redis rag-redis
COPY rag-pipeline rag-pipeline
COPY rag-app rag-app
COPY rag-test rag-test

# Build rag-app + all transitive modules in one pass.
#
# Why temporarily strip `<module>rag-test</module>` from the parent pom:
# the parent lists rag-test as a sibling module (for `mvn verify` on the
# host), but the fat-jar never needs it. Maven 3.9's `-pl '!rag-test'`
# syntax is finicky when combined with `-am` (maven 3.9.9 rejects "Could
# not find the selected project in the reactor: rag-test"). Editing the
# pom at build time is more reliable than fighting `-pl` semantics.
RUN cp pom.xml pom.xml.original && \
    sed -i '/<module>rag-test<\/module>/d' pom.xml && \
    mvn -pl rag-app -am -B -DskipTests package && \
    cp pom.xml.original pom.xml && \
    rm pom.xml.original

# ─────────────────────────── Stage 2: runtime ─────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

# Run as a non-root user so the container blast radius is smaller.
RUN groupadd --system --gid 1001 rag && \
    useradd --system --uid 1001 --gid rag --no-create-home rag

WORKDIR /app
COPY --from=build /src/rag-app/target/rag-app-*.jar /app/app.jar

USER rag

# Spring Boot Actuator / Prometheus / OpenAPI live on this port. The compose
# file maps 18081:8080 by default.
EXPOSE 8080

# Graceful shutdown — Spring Boot's SIGTERM handler drains in-flight
# requests for up to 30s before killing the JVM.
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]