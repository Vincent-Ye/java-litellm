# Build from repo root:  docker build -f litellm-proxy/Dockerfile -t java-litellm-proxy .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -DskipTests -Dspotless.check.skip=true package -pl litellm-proxy -am

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/litellm-proxy/target/litellm-proxy-*.jar app.jar
EXPOSE 4000
ENTRYPOINT ["java", "-jar", "app.jar"]
