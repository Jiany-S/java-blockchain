# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon :app:installDist

FROM eclipse-temurin:21-jre
ENV DATA_DIR=/data/chain \
    API_BIND=0.0.0.0 \
    API_PORT=8080 \
    RPC_BIND=0.0.0.0 \
    RPC_PORT=9090 \
    P2P_PORT=9000
WORKDIR /opt/app
COPY --from=build /workspace/app/build/install/app /opt/app
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
VOLUME ["/data"]
EXPOSE 8080 9090 9000
ENTRYPOINT ["/entrypoint.sh"]
