# Multi-stage build: Maven (Temurin 17) → JRE runtime.
# Image build uses -DskipTests for speed; CI must run `mvn test`.
# Lab / demo only — not a production hardening baseline.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY console-ui/package.json console-ui/package-lock.json console-ui/
COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/db-kernel-gateway-egine-*.jar /app/gateway.jar
RUN mkdir -p /app/data/console /app/audit
ENV SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=docker \
    GATEWAY_CONSOLE_DB_PATH=/app/data/console/gateway-console
EXPOSE 8080 33307 35433
VOLUME ["/app/data/console", "/app/audit"]
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
