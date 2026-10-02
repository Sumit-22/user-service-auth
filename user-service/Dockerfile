FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/user-service-1.0.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
