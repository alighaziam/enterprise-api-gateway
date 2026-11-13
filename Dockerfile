FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system gateway && useradd --system --gid gateway --uid 10001 gateway
COPY --chown=gateway:gateway target/enterprise-api-gateway-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:gateway
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
