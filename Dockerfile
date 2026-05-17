# ──────────────────────────────────────────────────────────────
#  AroundU Backend – Multi-stage Docker build
#  Base: Amazon Corretto 21 (matches local dev JDK)
# ──────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────
FROM amazoncorretto:21 AS build

RUN yum install -y tar gzip && yum clean all

WORKDIR /app

# Cache Maven wrapper + dependencies before copying source
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && \
    ./mvnw dependency:go-offline -B -q

# Copy source and build (skip tests – they run in CI)
COPY src src
RUN ./mvnw package -Dmaven.test.skip=true -B -q && \
    mv target/*.jar target/app.jar

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM amazoncorretto:21-alpine AS runtime

RUN apk upgrade --no-cache && \
    addgroup -S aroundu && adduser -S aroundu -G aroundu

WORKDIR /app

# Copy only the fat JAR from the build stage
COPY --from=build /app/target/app.jar app.jar

# Create log directory
RUN mkdir -p /app/logs && chown -R aroundu:aroundu /app

USER aroundu

EXPOSE 8080

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
