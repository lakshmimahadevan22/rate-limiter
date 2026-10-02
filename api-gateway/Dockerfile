# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY demo-backend/pom.xml demo-backend/pom.xml
RUN mvn -q -pl api-gateway -am dependency:go-offline

COPY api-gateway/src api-gateway/src
RUN mvn -q -pl api-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl \
    && addgroup -S spring && adduser -S spring -G spring
COPY --from=build /workspace/api-gateway/target/api-gateway-*.jar app.jar
USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
