# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY demo-backend/pom.xml demo-backend/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
RUN mvn -q -pl demo-backend -am dependency:go-offline

COPY demo-backend/src demo-backend/src
RUN mvn -q -pl demo-backend -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl \
    && addgroup -S spring && adduser -S spring -G spring
COPY --from=build /workspace/demo-backend/target/demo-backend-*.jar app.jar
USER spring:spring
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
