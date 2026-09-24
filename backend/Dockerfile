# Multi-stage build for Java 21 / Spring Boot 3
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# Production minimal JRE container
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache fontconfig ttf-dejavu
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-XX:+UseZGC", "-jar", "app.jar"]
