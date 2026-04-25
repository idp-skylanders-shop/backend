FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

COPY target/scala-3.3.1/email-worker.jar email-worker.jar

CMD ["java", "-cp", "email-worker.jar", "app.EmailWorker"]
