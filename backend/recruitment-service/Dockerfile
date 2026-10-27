FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/recruitment-service/pom.xml pom.xml
COPY backend/recruitment-service/src src
RUN mvn -B --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN mkdir -p /app/resume-storage && chown 10001:10001 /app/resume-storage
COPY --from=build /build/target/recruitment-service-*.jar app.jar
USER 10001:10001
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
