FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system gateway && useradd --system --gid gateway --uid 10001 gateway
COPY --from=build --chown=gateway:gateway /workspace/target/enterprise-api-gateway-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:gateway
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
