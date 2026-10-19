# 1-bosqich: yig'ish
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# 2-bosqich: ishga tushirish
FROM eclipse-temurin:21-jre
WORKDIR /app
# Ilova /app ichida yozadi: uploads/ (rasmlar) va data/ (H2 baza, agar PostgreSQL ulanmagan bo'lsa)
RUN useradd -r -u 1001 yukgo && mkdir -p /app/uploads /app/data && chown -R yukgo /app
COPY --from=build /src/target/yukgo-*.jar app.jar
USER yukgo
ENV UPLOADS_DIR=/app/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]