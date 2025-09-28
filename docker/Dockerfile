# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew gradle/ gradle.properties settings.gradle.kts ./
COPY app/build.gradle.kts app/
RUN ./gradlew --no-daemon dependencies
COPY . .
RUN ./gradlew --no-daemon :app:installDist

FROM eclipse-temurin:21-jre-jammy
ENV DATA_DIR=/data/chain \
    API_BIND=0.0.0.0 \
    API_PORT=8080 \
    RPC_BIND=0.0.0.0 \
    RPC_PORT=9090 \
    P2P_PORT=9000
WORKDIR /opt/app

# Create non-root user
RUN addgroup --system appgroup && adduser --system appuser --ingroup appgroup
USER appuser

COPY --from=build /workspace/app/build/install/app /opt/app
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

VOLUME ["/data"]
EXPOSE 8080 9090 9000
HEALTHCHECK --interval=30s --timeout=10s --start-period=15s CMD curl -f http://localhost:8080/status || exit 1

ENTRYPOINT ["/entrypoint.sh"]
