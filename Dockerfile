FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 gateway
COPY target/enterprise-api-gateway-0.1.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]