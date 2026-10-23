FROM m.daocloud.io/docker.io/library/eclipse-temurin:8-jre
WORKDIR /app
COPY target/ops-platform-api-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
