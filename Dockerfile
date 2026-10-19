FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system repomind && useradd --system --gid repomind repomind \
    && mkdir -p /var/lib/repomind/repositories && chown -R repomind:repomind /var/lib/repomind
WORKDIR /app
COPY --from=build /workspace/target/repomind-lite-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
USER repomind
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
