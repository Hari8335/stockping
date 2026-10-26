FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY shared-security shared-security
COPY contracts/java/pom.xml contracts/java/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml
COPY demand-service/pom.xml demand-service/pom.xml
COPY contracts/proto contracts/proto
COPY contracts/java/src contracts/java/src
COPY demand-service/src demand-service/src
RUN --mount=type=cache,id=stockping-maven,target=/root/.m2,sharing=locked mvn -B -ntp -pl demand-service -am verify -DexcludedGroups=postgres

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/demand-service/target/demand-service-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
