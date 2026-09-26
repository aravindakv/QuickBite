# Runtime-only image: the jar is built by Gradle on the host (fast incremental builds, shared Gradle cache).
FROM eclipse-temurin:25-jre

# tini = tiny init as PID 1: forwards SIGTERM to the JVM (graceful shutdown) and reaps zombie processes.
# curl = used by the container healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tini curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home app

WORKDIR /app
ARG JAR_FILE
COPY ${JAR_FILE} /app/app.jar

USER 10001

# Container-aware JVM:
#  MaxRAMPercentage=75   -> heap = 75% of the container limit (the rest is metaspace, threads, direct buffers)
#  UseZGC                -> generational ZGC: sub-millisecond pauses (low tail latency)
#  ExitOnOutOfMemoryError-> a JVM that hit OOM is in an undefined state; die and get restarted instead
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

ENTRYPOINT ["tini", "--", "java", "-jar", "/app/app.jar"]