FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

COPY target/scala-3.3.1/app.jar app.jar

CMD ["java", "-cp", "app.jar", "app.App"]
