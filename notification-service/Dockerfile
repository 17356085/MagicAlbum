FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml /workspace/pom.xml
COPY notification-service/pom.xml /workspace/notification-service/pom.xml

RUN mvn -q -e -pl notification-service -am dependency:go-offline

COPY notification-service/src /workspace/notification-service/src

RUN mvn -q -e -pl notification-service -am package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_OPTS=""
EXPOSE 8081

COPY --from=build /workspace/notification-service/target/*.jar /app/app.jar

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
