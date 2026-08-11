# Build stage — compiles the app with Maven and Java 17
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage — smaller image with just the Java runtime and the built jar
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/fraud-backend-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]