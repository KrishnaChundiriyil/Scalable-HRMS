FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/leave-service/pom.xml pom.xml
COPY backend/leave-service/src src
RUN mvn -B --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/target/leave-service-*.jar app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
