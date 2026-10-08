FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml /workspace/pom.xml
COPY backend/pom.xml /workspace/backend/pom.xml

RUN mvn -q -e -pl backend -am dependency:go-offline

COPY backend/src /workspace/backend/src

RUN mvn -q -e -pl backend -am package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_OPTS=""
EXPOSE 8080
EXPOSE 9090

COPY --from=build /workspace/backend/target/*.jar /app/app.jar

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
