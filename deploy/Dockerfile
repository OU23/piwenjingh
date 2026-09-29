FROM eclipse-temurin:17-jre

WORKDIR /app

RUN mkdir -p /data/files /data/chunks

COPY target/toolbox-1.0.0.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
