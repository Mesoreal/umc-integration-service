# ─── Stage 1: Build ───────────────────────────────────────────
FROM maven:3.9.15-eclipse-temurin-25 AS builder
WORKDIR /app

# Copy POM first — Docker caches this layer and only re-downloads
# dependencies when pom.xml actually changes (not on every src edit).
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

COPY src src
RUN mvn package -DskipTests -B -q

# ─── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS runtime

# Security: run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# Tune JVM for containerised environments:
#   -XX:+UseContainerSupport   — respect cgroup CPU/memory limits
#   -XX:MaxRAMPercentage=75    — use up to 75% of container RAM for heap
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

USER appuser
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
