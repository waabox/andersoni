FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/andersoni-admin-*.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
