FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# The shaded, self-contained node jar (built by maven-shade-plugin as app.jar).
COPY app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
