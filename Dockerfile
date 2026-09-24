# Multi-stage build for Java 21 / Spring Boot 3
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy root pom and backend pom
COPY pom.xml ./pom.xml
COPY backend/pom.xml ./backend/pom.xml
RUN mvn dependency:go-offline -B -f backend/pom.xml || true

# Copy frontend assets and backend source code
COPY frontend ./frontend
COPY backend/src ./backend/src

# Build and package the Spring Boot JAR with frontend assets
RUN mvn clean package -DskipTests -f backend/pom.xml

# Production minimal JRE container
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache fontconfig ttf-dejavu
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/backend/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-XX:+UseZGC", "-jar", "app.jar"]
