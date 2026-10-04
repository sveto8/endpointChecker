# --- 1. build ---
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# --- 2. runtime ---
FROM eclipse-temurin:11-jre
RUN useradd -r -u 10001 app && mkdir /data && chown app /data
WORKDIR /app
COPY --from=build /app/target/endpoint-checker.jar app.jar
USER app
ENV BIND_ADDRESS=0.0.0.0 PORT=8080 BLOCK_PRIVATE=true OPEN_BROWSER=false ALLOW_SHUTDOWN=false DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
