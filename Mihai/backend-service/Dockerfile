# Stage 1: compile
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.10_7_1.10.4_3.3.4 AS builder
WORKDIR /build

# Cache dependency resolution layer
COPY project/ project/
COPY build.sbt .
RUN sbt update

COPY src/ src/
RUN sbt assembly

# Stage 2: minimal runtime image
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /build/target/scala-3.3.1/app.jar app.jar
EXPOSE 8080
CMD ["java", "-cp", "app.jar", "app.App"]
