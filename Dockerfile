FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system railflow && useradd --system --gid railflow railflow
COPY --from=build /build/target/railflow-2.0.0.jar /app/railflow.jar
USER railflow
ENV BIND_ADDRESS=0.0.0.0
EXPOSE 8011
ENTRYPOINT ["java", "-jar", "/app/railflow.jar"]
